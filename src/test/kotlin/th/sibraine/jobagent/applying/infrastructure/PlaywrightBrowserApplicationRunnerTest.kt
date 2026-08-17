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
        } finally {
            runner.close()
            browser.close()
            playwright.close()
        }
    }
}
