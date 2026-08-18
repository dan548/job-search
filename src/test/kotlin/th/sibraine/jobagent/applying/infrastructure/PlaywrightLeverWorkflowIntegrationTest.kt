package th.sibraine.jobagent.applying.infrastructure

import com.microsoft.playwright.Playwright
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import th.sibraine.jobagent.applying.domain.BrowserFillAction
import th.sibraine.jobagent.applying.domain.BrowserFillCommand
import th.sibraine.jobagent.applying.domain.BrowserFormSnapshot
import th.sibraine.jobagent.applying.domain.BrowserInspectCommand
import th.sibraine.jobagent.applying.domain.BrowserSubmitCommand
import th.sibraine.jobagent.applying.domain.FormFieldType
import th.sibraine.jobagent.applying.domain.ObservedFormField
import th.sibraine.jobagent.applying.domain.SubmissionMode
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.Executors

class PlaywrightLeverWorkflowIntegrationTest {
    @Test
    fun `fills a dynamic Lever style form rescans new questions and submits after approval`() {
        assumeBrowserInstalled()
        val executor = Executors.newSingleThreadExecutor()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/lever/apply") { exchange ->
                val response = LEVER_FIXTURE.toByteArray(StandardCharsets.UTF_8)
                exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
                exchange.sendResponseHeaders(200, response.size.toLong())
                exchange.responseBody.use { it.write(response) }
            }
            this.executor = executor
            start()
        }
        val runner = PlaywrightBrowserApplicationRunner(
            PlaywrightBrowserProperties(
                allowedHosts = listOf("127.0.0.1"),
                allowHttpLocalhost = true,
                navigationTimeoutMs = 5_000.0,
                actionTimeoutMs = 5_000.0,
            )
        )
        try {
            val draftId = UUID.randomUUID()
            val runId = UUID.randomUUID()
            val formUrl = "http://127.0.0.1:${server.address.port}/lever/apply"
            val first = runner.inspect(BrowserInspectCommand(draftId, formUrl, "inspect-1"))

            assertEquals(setOf("name", "email"), first.fields.map { it.fieldKey }.toSet())
            val firstFill = runner.fill(
                BrowserFillCommand(
                    draftId = draftId,
                    runId = runId,
                    idempotencyKey = "fill-1",
                    expectedCheckpoint = first.checkpoint,
                    actions = listOf(
                        first.action("name", "Ada Lovelace"),
                        first.action("email", "ada@example.com"),
                    ),
                )
            )

            assertEquals(3, firstFill.validationErrors.size)
            val second = runner.inspect(BrowserInspectCommand(draftId, formUrl, "inspect-2"))
            assertEquals(5, second.fields.size)
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
            assertEquals(formUrl, receipt.reference)
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
        } finally {
            runner.close()
            server.stop(0)
            executor.shutdownNow()
        }
    }

    private fun BrowserFormSnapshot.fieldContaining(part: String) = fields.single { it.fieldKey.contains(part) }

    private fun BrowserFormSnapshot.action(fieldKey: String, value: String) = fields.single { it.fieldKey == fieldKey }
        .action(value = value)

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
        val LEVER_FIXTURE = """
            <!doctype html>
            <html lang="en">
              <body>
                <form id="application-form">
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
                  <button id="submit" type="submit" hidden>Submit application</button>
                </form>
                <section id="success" hidden><h1>Thank you for applying</h1></section>
                <style>.visually-hidden { position:absolute; width:1px; height:1px; opacity:0 }</style>
                <script>
                  const form = document.getElementById('application-form');
                  const custom = document.getElementById('custom-questions');
                  const submit = document.getElementById('submit');
                  const refresh = () => {
                    custom.hidden = !(form.elements.name.value && form.elements.email.validity.valid);
                    const customReady = !custom.hidden && Array.from(custom.querySelectorAll('textarea, input'))
                      .every(control => !control.required || control.type === 'radio'
                        ? form.elements[control.name].value
                        : control.validity.valid);
                    submit.hidden = !customReady;
                  };
                  form.addEventListener('input', refresh);
                  form.addEventListener('change', refresh);
                  form.addEventListener('submit', event => {
                    event.preventDefault();
                    if (!form.checkValidity()) return;
                    form.hidden = true;
                    document.getElementById('success').hidden = false;
                  });
                </script>
              </body>
            </html>
        """.trimIndent()
    }
}
