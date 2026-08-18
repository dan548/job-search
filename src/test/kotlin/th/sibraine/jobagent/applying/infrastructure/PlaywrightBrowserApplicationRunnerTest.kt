package th.sibraine.jobagent.applying.infrastructure

import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import th.sibraine.jobagent.applying.domain.BrowserArtifactPayload
import th.sibraine.jobagent.applying.domain.BrowserChallenge
import th.sibraine.jobagent.applying.domain.BrowserFillAction
import th.sibraine.jobagent.applying.domain.FormFieldType
import java.util.UUID

class PlaywrightBrowserApplicationRunnerTest {
    @Test
    fun `discovers semantic form controls and browser challenges in a real page`() = withRunner(
        PlaywrightBrowserProperties(allowedHosts = listOf("jobs.example.com"))
    ) { runner, page ->
        page.setContent(
            """
            <form>
              <label for="name">Full name</label><input id="name" required placeholder="Ada Lovelace">
              <input type="hidden" name="csrf" value="secret">
              <label for="country">Country</label>
              <select id="country"><option>Poland</option><option>Germany</option></select>
              <fieldset><legend>Are you authorized to work?</legend>
                <label><input type="radio" name="authorization" value="Yes" required>Yes</label>
                <label><input type="radio" name="authorization" value="No">No</label>
              </fieldset>
              <label><input id="terms" type="checkbox" required>Accept terms</label>
              <label for="cv">Resume</label><input id="cv" type="file">
              <input id="otp" autocomplete="one-time-code">
              <div id="captcha" style="width:100px;height:20px">Challenge</div>
              <button>Attach another file</button>
              <button type="submit">Submit application</button>
            </form>
            """.trimIndent()
        )

        val fields = runner.observeFields(page)

        assertEquals(6, fields.size)
        assertEquals("Full name", fields.single { it.fieldKey == "name" }.label)
        assertEquals(FormFieldType.SELECT, fields.single { it.fieldKey == "country" }.type)
        assertEquals(listOf("Poland", "Germany"), fields.single { it.fieldKey == "country" }.options)
        val radio = fields.single { it.fieldKey == "authorization" }
        assertEquals("Are you authorized to work?", radio.label)
        assertEquals(listOf("Yes", "No"), radio.options)
        assertTrue(radio.required)
        assertEquals(FormFieldType.FILE, fields.single { it.fieldKey == "cv" }.type)
        assertTrue(fields.all { page.locator(it.locator!!).count() >= 1 })
        assertEquals(
            setOf(BrowserChallenge.CAPTCHA, BrowserChallenge.OTP),
            runner.detectChallenges(page),
        )
        assertTrue(runner.validationErrors(page).any { it.fieldKey == "name" })
        assertEquals("Submit application", runner.submitControl(page).innerText())
    }

    @Test
    fun `discovers labels and control types in Lever style custom question cards`() = withRunner(
        PlaywrightBrowserProperties(allowedHosts = listOf("jobs.lever.co"))
    ) { runner, page ->
        page.setContent(
            """
            <form>
              <ul>
                <li class="application-question custom-question">
                  <label>Please describe your hands-on experience with Java/Kotlin on JVM.✱</label>
                  <div class="application-field">
                    <textarea name="cards[92f8298a-5654-43d2-a77c-a5919fa8d5e9][field0]" required></textarea>
                  </div>
                </li>
                <li class="application-question custom-question">
                  <label>Which working model do you prefer?✱</label>
                  <div class="application-field">
                    <label><input type="radio" name="cards[card][field1]" value="Remote" required>Remote</label>
                    <label><input type="radio" name="cards[card][field1]" value="Office">Office</label>
                  </div>
                </li>
                <li class="application-question custom-question">
                  <label>By applying, you agree to the Candidate Privacy Notice.✱</label>
                  <div class="application-field">
                    <label><input class="visually-hidden" type="checkbox" name="cards[card][field2]" required>Confirm</label>
                  </div>
                </li>
              </ul>
              <button type="submit">Submit application</button>
            </form>
            <style>.visually-hidden { position:absolute; width:1px; height:1px; opacity:0 }</style>
            """.trimIndent()
        )

        val fields = runner.observeFields(page)

        assertEquals(3, fields.size)
        val experience = fields.single { it.fieldKey.contains("field0") }
        assertEquals(
            "Please describe your hands-on experience with Java/Kotlin on JVM.✱",
            experience.label,
        )
        assertEquals(FormFieldType.TEXTAREA, experience.type)
        assertTrue(experience.required)

        val workingModel = fields.single { it.fieldKey.contains("field1") }
        assertEquals("Which working model do you prefer?✱", workingModel.label)
        assertEquals(FormFieldType.RADIO, workingModel.type)
        assertEquals(listOf("Remote", "Office"), workingModel.options)

        val consent = fields.single { it.fieldKey.contains("field2") }
        assertEquals("By applying, you agree to the Candidate Privacy Notice.✱", consent.label)
        assertEquals(FormFieldType.CHECKBOX, consent.type)
        assertTrue(consent.required)
        assertEquals(3, runner.validationErrors(page).size)
    }

    @Test
    fun `uses stable Lever attributes when a wrapping label has no for attribute`() = withRunner(
        PlaywrightBrowserProperties(allowedHosts = listOf("jobs.lever.co"))
    ) { runner, page ->
        page.setContent(
            """
            <form>
              <li class="application-question">
                <label>
                  <div class="application-label">Full name<span class="required">✱</span></div>
                  <div class="application-field">
                    <input type="text" data-qa="name-input" name="name" required>
                  </div>
                </label>
              </li>
            </form>
            """.trimIndent()
        )

        val name = runner.observeFields(page).single()

        assertEquals("name", name.fieldKey)
        assertEquals("Full name✱", name.label)
        assertEquals("input[data-qa=\"name-input\"]", name.locator)
        assertTrue(name.required)
    }

    @Test
    fun `fills a Lever field after resume parsing replaces the original control`() = withRunner(
        PlaywrightBrowserProperties(allowedHosts = listOf("jobs.lever.co"))
    ) { runner, page ->
        page.setContent(
            """
            <form>
              <label>Resume/CV✱<input name="resume" type="file"></label>
              <label>Full name✱<input type="text" data-qa="name-input" name="name" required></label>
            </form>
            """.trimIndent()
        )
        val fields = runner.observeFields(page).associateBy { it.fieldKey }
        val name = fields.getValue("name")

        page.evaluate(
            """() => {
              const previous = document.querySelector('input[name="name"]');
              const replacement = previous.cloneNode();
              replacement.value = 'Resume parser guess';
              previous.replaceWith(replacement);
            }"""
        )

        runner.apply(
            page,
            BrowserFillAction(name.fieldKey, name.locator!!, name.type, value = "Ada Lovelace"),
        )

        assertEquals("Ada Lovelace", page.locator("input[name=name]").inputValue())
    }

    @Test
    fun `discovers and fills a resume input that is hidden behind its own label`() = withRunner(
        PlaywrightBrowserProperties(allowedHosts = listOf("jobs.lever.co"))
    ) { runner, page ->
        page.setContent(
            """
            <form>
              <div class="application-question">
                <label for="resume-upload-input">Resume/CV✱</label>
                <input id="resume-upload-input" name="resume" type="file" required style="display:none">
                <div class="resume-upload-button">Attach resume</div>
              </div>
            </form>
            """.trimIndent()
        )

        val resume = runner.observeFields(page).single()
        assertEquals(FormFieldType.FILE, resume.type)
        assertEquals("Resume/CV✱", resume.label)
        assertTrue(resume.required)

        runner.apply(
            page,
            BrowserFillAction(
                fieldKey = resume.fieldKey,
                locator = resume.locator!!,
                type = resume.type,
                artifact = BrowserArtifactPayload(
                    artifactId = UUID.randomUUID(),
                    fileName = "resume.pdf",
                    contentType = "application/pdf",
                    content = "%PDF-1.4 resume".toByteArray(),
                ),
            ),
        )

        assertEquals(
            "resume.pdf",
            page.evaluate("() => document.querySelector('#resume-upload-input').files[0].name"),
        )
    }

    @Test
    fun `drops field keys left behind by an earlier observation`() = withRunner(
        PlaywrightBrowserProperties(allowedHosts = listOf("jobs.lever.co"))
    ) { runner, page ->
        page.setContent(
            """
            <form>
              <label for="name">Full name</label><input id="name">
              <label for="referral">Who referred you?</label><input id="referral">
            </form>
            """.trimIndent()
        )
        assertEquals(2, runner.observeFields(page).size)

        page.evaluate("() => { document.querySelector('#referral').style.display = 'none'; }")
        val fields = runner.observeFields(page)

        assertEquals(listOf("name"), fields.map { it.fieldKey })
        assertEquals(1, page.locator("[data-job-agent-field-key]").count())
    }

    @Test
    fun `prefers the Lever submit control over other submit buttons on the page`() = withRunner(
        PlaywrightBrowserProperties(allowedHosts = listOf("jobs.lever.co"))
    ) { runner, page ->
        page.setContent(
            """
            <form>
              <input id="name" value="Ada">
              <button type="submit" class="postings-btn">Save for later</button>
              <button id="btn-submit" type="submit" class="template-btn-submit">Submit application</button>
            </form>
            """.trimIndent()
        )

        assertEquals("Submit application", runner.submitControl(page).innerText())
    }

    @Test
    fun `reports the message a Lever question renders for a control the browser accepts`() = withRunner(
        PlaywrightBrowserProperties(allowedHosts = listOf("jobs.lever.co"))
    ) { runner, page ->
        page.setContent(
            """
            <form>
              <div class="application-question">
                <label for="salary">Expected salary✱</label>
                <input id="salary" aria-invalid="true">
                <div class="application-error">Please enter a number.</div>
              </div>
            </form>
            """.trimIndent()
        )
        runner.observeFields(page)

        val error = runner.validationErrors(page).single()

        assertEquals("salary", error.fieldKey)
        assertEquals("Please enter a number.", error.message)
    }

    @Test
    fun `selects a suggestion from a custom autocomplete instead of only filling its text`() = withRunner(
        PlaywrightBrowserProperties(allowedHosts = listOf("jobs.example.com"), actionTimeoutMs = 1_000.0)
    ) { runner, page ->
        page.setContent(
            """
            <label for="location">Current location *</label>
            <input id="location" role="combobox" aria-autocomplete="list" aria-controls="places" required>
            <div id="places" role="listbox" hidden>
              <div role="option">Bangkok, Thailand</div>
              <div role="option">Bangkok, United States</div>
            </div>
            <input id="selected-location" type="hidden">
            <script>
              const input = document.querySelector('#location');
              const list = document.querySelector('#places');
              input.addEventListener('input', () => { list.hidden = false; });
              list.addEventListener('click', event => {
                input.value = event.target.textContent;
                document.querySelector('#selected-location').value = 'selected';
                list.hidden = true;
              });
            </script>
            """.trimIndent()
        )

        val location = runner.observeFields(page).single()
        assertEquals(FormFieldType.COMBOBOX, location.type)

        runner.apply(
            page,
            BrowserFillAction(location.fieldKey, location.locator!!, location.type, value = "Bangkok"),
        )

        assertEquals("Bangkok, Thailand", page.locator("#location").inputValue())
        assertEquals("selected", page.locator("#selected-location").inputValue())
    }

    @Test
    fun `selects the canonical suggestion when the site expands the answered city`() = withRunner(
        PlaywrightBrowserProperties(allowedHosts = listOf("jobs.lever.co"), actionTimeoutMs = 1_000.0)
    ) { runner, page ->
        page.setContent(
            """
            <label for="location-input">Location✱</label>
            <input id="location-input" role="combobox" aria-autocomplete="list" required>
            <div class="dropdown-location">
              <div class="dropdown-location-option">Bangkok, Krung Thep Maha Nakhon, Thailand</div>
            </div>
            <script>
              document.querySelector('.dropdown-location').addEventListener('click', event => {
                document.querySelector('#location-input').value = event.target.textContent;
              });
            </script>
            """.trimIndent()
        )
        val location = runner.observeFields(page).single()

        runner.apply(
            page,
            BrowserFillAction(
                location.fieldKey, location.locator!!, location.type, value = "Bangkok, Thailand",
            ),
        )

        assertEquals("Bangkok, Krung Thep Maha Nakhon, Thailand", page.locator("#location-input").inputValue())
    }

    @Test
    fun `refuses to take an autocomplete suggestion unrelated to the approved answer`() = withRunner(
        PlaywrightBrowserProperties(allowedHosts = listOf("jobs.lever.co"), actionTimeoutMs = 300.0)
    ) { runner, page ->
        page.setContent(
            """
            <label for="location-input">Location✱</label>
            <input id="location-input" role="combobox" aria-autocomplete="list" required>
            <div class="dropdown-location">
              <div class="dropdown-location-option">Berlin, Germany</div>
              <div class="dropdown-location-option">Bern, Switzerland</div>
            </div>
            """.trimIndent()
        )
        val location = runner.observeFields(page).single()

        val error = assertThrows(BrowserAutocompleteException::class.java) {
            runner.apply(
                page,
                BrowserFillAction(
                    location.fieldKey, location.locator!!, location.type, value = "Bangkok, Thailand",
                ),
            )
        }

        assertTrue(error.message!!.contains("2 suggestion(s) were offered"), error.message)
        assertEquals("", page.locator("#location-input").inputValue().substringBefore("Bangkok"))
    }

    @Test
    fun `fails quickly when autocomplete suggestions never become available`() = withRunner(
        PlaywrightBrowserProperties(allowedHosts = listOf("jobs.example.com"), actionTimeoutMs = 200.0)
    ) { runner, page ->
        page.setContent(
            """
            <label for="location">Current location *</label>
            <input id="location" role="combobox" aria-autocomplete="list" required>
            <div role="listbox"><div>Loading</div></div>
            """.trimIndent()
        )
        val location = runner.observeFields(page).single()

        val error = assertThrows(BrowserAutocompleteException::class.java) {
            runner.apply(
                page,
                BrowserFillAction(location.fieldKey, location.locator!!, location.type, value = "Bangkok"),
            )
        }

        assertTrue(error.message!!.contains("did not become available"))
    }

    @Test
    fun `continues filling other controls when one autocomplete cannot load`() = withRunner(
        PlaywrightBrowserProperties(allowedHosts = listOf("jobs.example.com"), actionTimeoutMs = 200.0)
    ) { runner, page ->
        page.setContent(
            """
            <label for="location">Current location *</label>
            <input id="location" role="combobox" aria-autocomplete="list" required>
            <div role="listbox"><div>Loading</div></div>
            <label for="name">Full name</label>
            <input id="name" required>
            """.trimIndent()
        )
        val fields = runner.observeFields(page).associateBy { it.fieldKey }
        val location = fields.getValue("location")
        val name = fields.getValue("name")

        val (applied, errors) = runner.applyActions(
            page,
            listOf(
                BrowserFillAction(location.fieldKey, location.locator!!, location.type, value = "Bangkok"),
                BrowserFillAction(name.fieldKey, name.locator!!, name.type, value = "Ada Lovelace"),
            ),
        )

        assertEquals(listOf("name"), applied)
        assertEquals("Ada Lovelace", page.locator("#name").inputValue())
        assertEquals("AUTOCOMPLETE_OPTIONS_UNAVAILABLE", errors.single().code)
        assertEquals("location", errors.single().fieldKey)
    }

    /** Skips the test when no Chrome for Testing binary is installed, and always releases the browser. */
    private fun withRunner(
        properties: PlaywrightBrowserProperties,
        block: (PlaywrightBrowserApplicationRunner, Page) -> Unit,
    ) {
        val playwright = Playwright.create()
        val browser = runCatching { playwright.chromium().launch() }.getOrElse {
            playwright.close()
            assumeTrue(false, "Playwright browser is not installed: ${it.message}")
            return
        }
        val runner = PlaywrightBrowserApplicationRunner(properties)
        try {
            block(runner, browser.newPage())
        } finally {
            runner.close()
            browser.close()
            playwright.close()
        }
    }
}
