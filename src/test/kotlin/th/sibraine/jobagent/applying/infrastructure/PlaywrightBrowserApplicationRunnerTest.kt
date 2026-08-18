package th.sibraine.jobagent.applying.infrastructure

import com.microsoft.playwright.Playwright
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import th.sibraine.jobagent.applying.domain.BrowserChallenge
import th.sibraine.jobagent.applying.domain.FormFieldType

class PlaywrightBrowserApplicationRunnerTest {
    @Test
    fun `discovers semantic form controls and browser challenges in a real page`() {
        val playwright = Playwright.create()
        val browser = runCatching { playwright.chromium().launch() }.getOrElse {
            playwright.close()
            assumeTrue(false, "Playwright browser is not installed: ${it.message}")
            return
        }
        val runner = PlaywrightBrowserApplicationRunner(
            PlaywrightBrowserProperties(allowedHosts = listOf("jobs.example.com"))
        )
        try {
            val page = browser.newPage()
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
        } finally {
            runner.close()
            browser.close()
            playwright.close()
        }
    }

    @Test
    fun `discovers labels and control types in Lever style custom question cards`() {
        val playwright = Playwright.create()
        val browser = runCatching { playwright.chromium().launch() }.getOrElse {
            playwright.close()
            assumeTrue(false, "Playwright browser is not installed: ${it.message}")
            return
        }
        val runner = PlaywrightBrowserApplicationRunner(
            PlaywrightBrowserProperties(allowedHosts = listOf("jobs.lever.co"))
        )
        try {
            val page = browser.newPage()
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
        } finally {
            runner.close()
            browser.close()
            playwright.close()
        }
    }
}
