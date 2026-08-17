package th.sibraine.jobagent.applying.api

import th.sibraine.jobagent.applying.ApplicationWorkflowFixture
import th.sibraine.jobagent.applying.domain.*
import th.sibraine.jobagent.shared.ApiExceptionHandler
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.util.UUID

class BrowserApplicationRunApiTest {
    private val fixture = ApplicationWorkflowFixture()
    private val objectMapper: ObjectMapper = jacksonObjectMapper().findAndRegisterModules()
    private val runners = mockk<ObjectProvider<BrowserApplicationRunner>>()
    private val mvc: MockMvc = MockMvcBuilders
        .standaloneSetup(
            ApplicationDraftController(fixture.service),
            BrowserApplicationRunController(
                fixture.service,
                runners,
                SemanticFormFiller(),
                fixture.browserSessionService,
            ),
        )
        .setControllerAdvice(ApiExceptionHandler())
        .build()

    @Test
    fun `runs pauses and resumes a browser application over HTTP`() {
        val runner = StubRunner(challenges = setOf(BrowserChallenge.CAPTCHA))
        every { runners.getIfAvailable() } returns runner
        val draftId = createDraft()

        mvc.post("/api/v1/application-drafts/$draftId/browser-runs") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"formUrl": "https://jobs.example.com/apply/1", "idempotencyKey": "attempt-1"}"""
        }.andExpect {
            status { isCreated() }
            jsonPath("$.outcome") { value("PAUSED") }
            jsonPath("$.challenges[0]") { value("CAPTCHA") }
            jsonPath("$.session.status") { value("PAUSED") }
        }

        mvc.get("/api/v1/application-drafts/$draftId/browser-session")
            .andExpect {
                status { isOk() }
                jsonPath("$.status") { value("PAUSED") }
                jsonPath("$.currentUrl") { value("https://jobs.example.com/apply/1") }
            }

        runner.challenges = emptySet()
        mvc.post("/api/v1/application-drafts/$draftId/browser-runs/resume")
            .andExpect {
                status { isCreated() }
                jsonPath("$.outcome") { value("PAUSED") }
                // The salary question of the second step blocks the run, the rest was filled.
                jsonPath("$.application.draft.draft.status") { value("NEEDS_INPUT") }
                jsonPath("$.application.draft.pendingApprovals[0].fieldKey") { value("salary") }
                jsonPath("$.session.resumeCount") { value(1) }
            }

        mvc.get("/api/v1/application-drafts/$draftId/browser-audit")
            .andExpect {
                status { isOk() }
                jsonPath("$[0].event") { value("RUN_PAUSED") }
                jsonPath("$[0].detailCode") { value("CAPTCHA") }
            }
            .andReturn().response.contentAsString
            .let { body ->
                // Audit never carries an answer value or a file byte.
                assert(!body.contains("ada@example.com")) { "audit leaked an answer value" }
            }
    }

    @Test
    fun `refuses browser runs while no browser adapter is configured`() {
        every { runners.getIfAvailable() } returns null
        val draftId = createDraft()

        mvc.post("/api/v1/application-drafts/$draftId/browser-runs") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"formUrl": "https://jobs.example.com/apply/1", "idempotencyKey": "attempt-1"}"""
        }.andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("BROWSER_RUNNER_DISABLED") }
        }

        mvc.get("/api/v1/application-drafts/$draftId/browser-session")
            .andExpect {
                status { isNotFound() }
                jsonPath("$.code") { value("BROWSER_SESSION_NOT_FOUND") }
            }
    }

    private fun createDraft(): String {
        val created = mvc.post("/api/v1/vacancies/${fixture.vacancyId}/application-drafts")
            .andReturn().response.contentAsString
        return objectMapper.readTree(created)["draft"]["draftId"].asText()
    }

    private class StubRunner(var challenges: Set<BrowserChallenge> = emptySet()) : BrowserApplicationRunner {
        private var revision = 0

        override fun inspect(command: BrowserInspectCommand) = BrowserFormSnapshot(
            url = command.formUrl,
            fields = if (challenges.isEmpty()) fields() else emptyList(),
            checkpoint = "checkpoint-$revision",
            challenges = challenges,
        )

        override fun fill(command: BrowserFillCommand): BrowserFillResult {
            revision++
            return BrowserFillResult("checkpoint-$revision", command.actions.map { it.fieldKey })
        }

        override fun submit(command: BrowserSubmitCommand) = SubmissionReceipt(SubmissionMode.BROWSER)

        override fun exportState(draftId: UUID) =
            BrowserSessionState("https://jobs.example.com/apply/1", "cookie-jar")

        private fun fields() = listOf(
            ObservedFormField("email", "Email", FormFieldType.EMAIL, required = true, locator = "#email"),
            ObservedFormField("salary", "Desired salary", required = true, locator = "#salary"),
        )
    }
}
