package th.sibraine.jobagent.applying.infrastructure

import com.microsoft.playwright.Playwright
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import th.sibraine.jobagent.applying.domain.BrowserArtifactPayload
import th.sibraine.jobagent.applying.domain.BrowserFillAction
import th.sibraine.jobagent.applying.domain.BrowserFillCommand
import th.sibraine.jobagent.applying.domain.BrowserFormSnapshot
import th.sibraine.jobagent.applying.domain.BrowserInspectCommand
import th.sibraine.jobagent.applying.domain.BrowserSubmitCommand
import th.sibraine.jobagent.applying.domain.FormFieldType
import th.sibraine.jobagent.applying.domain.ObservedFormField
import th.sibraine.jobagent.applying.domain.SubmissionMode
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

class PlaywrightLeverWorkflowIntegrationTest {
    @Test
    fun `uploads the resume first fills the answers rescans new questions and submits after approval`() {
        assumeBrowserInstalled()
        LeverSite(APPLY_PAGE).use { site ->
            withRunner { runner ->
                val draftId = UUID.randomUUID()
                val runId = UUID.randomUUID()
                val first = runner.inspect(BrowserInspectCommand(draftId, site.applyUrl, "inspect-1"))

                assertEquals(setOf("resume-upload-input", "name", "email"), first.fields.map { it.fieldKey }.toSet())
                assertEquals(FormFieldType.FILE, first.field("resume-upload-input").type)

                // The resume is listed last on purpose: Lever answers the upload with the fields it
                // parsed out of the file, so a runner that uploads last submits the parser's guesses.
                val firstFill = runner.fill(
                    BrowserFillCommand(
                        draftId = draftId,
                        runId = runId,
                        idempotencyKey = "fill-1",
                        expectedCheckpoint = first.checkpoint,
                        actions = listOf(
                            first.action("name", "Ada Lovelace"),
                            first.action("email", "ada@example.com"),
                            first.resumeAction(),
                        ),
                    )
                )

                assertEquals(
                    listOf("resume-upload-input", "name", "email"),
                    firstFill.appliedFieldKeys,
                )
                assertEquals(3, firstFill.validationErrors.size)
                val second = runner.inspect(BrowserInspectCommand(draftId, site.applyUrl, "inspect-2"))
                assertEquals(6, second.fields.size)
                assertEquals(
                    "Please describe your hands-on Java/Kotlin experience.✱",
                    second.fieldContaining("field0").label,
                )
                assertEquals(listOf("Remote", "Office"), second.fieldContaining("field1").options)

                val secondFill = runner.fill(
                    BrowserFillCommand(
                        draftId = draftId,
                        runId = runId,
                        idempotencyKey = "fill-2",
                        expectedCheckpoint = second.checkpoint,
                        actions = listOf(
                            second.actionContaining("field0", "Built JVM services for five years"),
                            second.actionContaining("field1", "Remote"),
                            second.actionContaining("field2", checked = true),
                        ),
                    )
                )

                assertTrue(secondFill.validationErrors.isEmpty())
                val receipt = runner.submit(
                    BrowserSubmitCommand(
                        draftId = draftId,
                        runId = runId,
                        idempotencyKey = "submit-1",
                        expectedCheckpoint = secondFill.checkpoint,
                        approvedStateFingerprint = "approved-fixture-state",
                    )
                )
                assertEquals(SubmissionMode.BROWSER, receipt.mode)
                assertEquals(site.url("/lever/thanks"), receipt.reference)
                assertEquals(
                    receipt,
                    runner.submit(
                        BrowserSubmitCommand(
                            draftId,
                            runId,
                            "submit-1",
                            secondFill.checkpoint,
                            "approved-fixture-state",
                        )
                    ),
                )

                val submitted = site.submittedForm.get()
                assertTrue(submitted.contains("name=Ada Lovelace"), submitted)
                assertTrue(submitted.contains("email=ada@example.com"), submitted)
                assertFalse(submitted.contains(PARSED_NAME), submitted)
                assertTrue(submitted.contains("resume=resume.pdf"), submitted)
            }
        }
    }

    @Test
    fun `reports the error banner instead of claiming that the application was submitted`() {
        assumeBrowserInstalled()
        LeverSite(CLOSED_POSTING_PAGE).use { site ->
            withRunner { runner ->
                val draftId = UUID.randomUUID()
                val runId = UUID.randomUUID()
                val snapshot = runner.inspect(BrowserInspectCommand(draftId, site.applyUrl, "inspect-1"))
                val fill = runner.fill(
                    BrowserFillCommand(
                        draftId = draftId,
                        runId = runId,
                        idempotencyKey = "fill-1",
                        expectedCheckpoint = snapshot.checkpoint,
                        actions = listOf(snapshot.action("name", "Ada Lovelace")),
                    )
                )

                val error = assertThrows(IllegalStateException::class.java) {
                    runner.submit(
                        BrowserSubmitCommand(
                            draftId = draftId,
                            runId = runId,
                            idempotencyKey = "submit-1",
                            expectedCheckpoint = fill.checkpoint,
                            approvedStateFingerprint = "approved-fixture-state",
                        )
                    )
                }

                assertTrue(
                    error.message!!.contains("This posting is no longer accepting applications"),
                    error.message,
                )
            }
        }
    }

    private fun withRunner(block: (PlaywrightBrowserApplicationRunner) -> Unit) {
        val runner = PlaywrightBrowserApplicationRunner(
            PlaywrightBrowserProperties(
                allowedHosts = listOf("127.0.0.1"),
                allowHttpLocalhost = true,
                navigationTimeoutMs = 5_000.0,
                actionTimeoutMs = 5_000.0,
            )
        )
        try {
            block(runner)
        } finally {
            runner.close()
        }
    }

    /** A local stand-in for a Lever posting: the apply page, its resume parser and its thanks page. */
    private class LeverSite(applyPage: String) : AutoCloseable {
        val submittedForm = AtomicReference("")
        private val executor = Executors.newFixedThreadPool(2)
        private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/lever/apply") { it.respond(applyPage) }
            createContext("/lever/parse") { exchange ->
                Thread.sleep(PARSE_DELAY_MS)
                exchange.respond(PARSED_RESUME, "application/json")
            }
            createContext("/lever/thanks") { exchange ->
                val body = exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8)
                submittedForm.set(URLDecoder.decode(body, StandardCharsets.UTF_8))
                exchange.respond("<h1>Thank you for applying</h1>")
            }
            this.executor = this@LeverSite.executor
            start()
        }

        val applyUrl: String get() = url("/lever/apply")

        fun url(path: String): String = "http://127.0.0.1:${server.address.port}$path"

        override fun close() {
            server.stop(0)
            executor.shutdownNow()
        }

        private fun HttpExchange.respond(body: String, contentType: String = "text/html; charset=utf-8") {
            val bytes = body.toByteArray(StandardCharsets.UTF_8)
            responseHeaders.add("Content-Type", contentType)
            sendResponseHeaders(200, bytes.size.toLong())
            responseBody.use { it.write(bytes) }
        }
    }

    private fun BrowserFormSnapshot.field(fieldKey: String) = fields.single { it.fieldKey == fieldKey }

    private fun BrowserFormSnapshot.fieldContaining(part: String) = fields.single { it.fieldKey.contains(part) }

    private fun BrowserFormSnapshot.action(fieldKey: String, value: String) = field(fieldKey).action(value = value)

    private fun BrowserFormSnapshot.resumeAction() = field("resume-upload-input").let { resume ->
        BrowserFillAction(
            fieldKey = resume.fieldKey,
            locator = requireNotNull(resume.locator),
            type = resume.type,
            artifact = BrowserArtifactPayload(
                artifactId = UUID.randomUUID(),
                fileName = "resume.pdf",
                contentType = "application/pdf",
                content = "%PDF-1.4 tailored resume".toByteArray(),
            ),
        )
    }

    private fun BrowserFormSnapshot.actionContaining(
        fieldKeyPart: String,
        value: String? = null,
        checked: Boolean? = null,
    ) = fieldContaining(fieldKeyPart).action(value, checked)

    private fun ObservedFormField.action(value: String? = null, checked: Boolean? = null) = BrowserFillAction(
        fieldKey = fieldKey,
        locator = requireNotNull(locator),
        type = type,
        value = value,
        checked = checked,
    )

    private fun assumeBrowserInstalled() {
        val playwright = Playwright.create()
        val browser = runCatching { playwright.chromium().launch() }.getOrElse {
            playwright.close()
            assumeTrue(false, "Playwright browser is not installed: ${it.message}")
            return
        }
        browser.close()
        playwright.close()
    }

    private companion object {
        const val PARSE_DELAY_MS = 200L
        const val PARSED_NAME = "Resume Parser Guess"
        val PARSED_RESUME = """
            {"name": "$PARSED_NAME", "email": "parsed@resume.example", "fileName": "resume.pdf"}
        """.trimIndent()

        val APPLY_PAGE = """
            <!doctype html>
            <html lang="en">
              <body>
                <form id="application-form" method="post" action="/lever/thanks">
                  <div class="application-question">
                    <label for="resume-upload-input">Resume/CV✱</label>
                    <input id="resume-upload-input" name="resume" type="file" required style="display:none">
                    <div class="resume-upload-button">Attach resume</div>
                    <div id="resume-filename"></div>
                  </div>
                  <label for="name">Full name</label>
                  <input id="name" name="name" required>
                  <label for="email">Email</label>
                  <input id="email" name="email" type="email" required>

                  <section id="custom-questions" hidden>
                    <div class="application-question custom-question">
                      <label>Please describe your hands-on Java/Kotlin experience.✱</label>
                      <div class="application-field">
                        <textarea name="cards[fixture-card][field0]" required></textarea>
                      </div>
                    </div>
                    <div class="application-question custom-question">
                      <label>Which working model do you prefer?✱</label>
                      <div class="application-field">
                        <label><input type="radio" name="cards[fixture-card][field1]" value="Remote" required>Remote</label>
                        <label><input type="radio" name="cards[fixture-card][field1]" value="Office">Office</label>
                      </div>
                    </div>
                    <div class="application-question custom-question">
                      <label>Confirm the Candidate Privacy Notice.✱</label>
                      <div class="application-field">
                        <label><input class="visually-hidden" type="checkbox" name="cards[fixture-card][field2]" required>Confirm</label>
                      </div>
                    </div>
                  </section>
                  <button type="submit" class="postings-btn">Save for later</button>
                  <button id="btn-submit" class="template-btn-submit" type="submit" disabled>Submit application</button>
                </form>
                <style>.visually-hidden { position:absolute; width:1px; height:1px; opacity:0 }</style>
                <script>
                  const form = document.getElementById('application-form');
                  const custom = document.getElementById('custom-questions');
                  const submit = document.getElementById('btn-submit');
                  const refresh = () => {
                    custom.hidden = !(form.elements.name.value && form.elements.email.validity.valid);
                    submit.disabled = !form.checkValidity();
                  };
                  form.elements.resume.addEventListener('change', async () => {
                    // Lever uploads the file, parses it and then overwrites the contact fields with
                    // whatever its parser read, exactly like this handler does.
                    const parsed = await (await fetch('/lever/parse')).json();
                    form.elements.name.value = parsed.name;
                    form.elements.email.value = parsed.email;
                    document.getElementById('resume-filename').textContent = parsed.fileName;
                    refresh();
                  });
                  form.addEventListener('input', refresh);
                  form.addEventListener('change', refresh);
                </script>
              </body>
            </html>
        """.trimIndent()

        val CLOSED_POSTING_PAGE = """
            <!doctype html>
            <html lang="en">
              <body>
                <form id="application-form" method="post" action="/lever/thanks">
                  <label for="name">Full name</label>
                  <input id="name" name="name" required>
                  <button id="btn-submit" class="template-btn-submit" type="submit">Submit application</button>
                </form>
                <div class="postings-btn-error" hidden></div>
                <script>
                  const banner = document.querySelector('.postings-btn-error');
                  document.getElementById('application-form').addEventListener('submit', event => {
                    event.preventDefault();
                    banner.textContent = 'This posting is no longer accepting applications.';
                    banner.hidden = false;
                  });
                </script>
              </body>
            </html>
        """.trimIndent()
    }
}
