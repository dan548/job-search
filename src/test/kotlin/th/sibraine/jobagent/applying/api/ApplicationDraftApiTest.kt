package th.sibraine.jobagent.applying.api

import th.sibraine.jobagent.applying.ApplicationWorkflowFixture
import th.sibraine.jobagent.shared.ApiExceptionHandler
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.setup.MockMvcBuilders

class ApplicationDraftApiTest {
    private val fixture = ApplicationWorkflowFixture()
    private val objectMapper: ObjectMapper = jacksonObjectMapper().findAndRegisterModules()
    private val mvc: MockMvc = MockMvcBuilders.standaloneSetup(ApplicationDraftController(fixture.service))
        .setControllerAdvice(ApiExceptionHandler())
        .build()

    @Test
    fun `runs the whole application workflow over HTTP`() {
        val created = mvc.post("/api/v1/vacancies/${fixture.vacancyId}/application-drafts")
            .andExpect {
                status { isCreated() }
                jsonPath("$.draft.status") { value("DRAFT") }
                jsonPath("$.draft.resumeVariantId") { value(fixture.variantId.toString()) }
            }
            .andReturn().response.contentAsString
        val draftId = objectMapper.readTree(created)["draft"]["draftId"].asText()

        mvc.post("/api/v1/application-drafts/$draftId/runs") {
            contentType = MediaType.APPLICATION_JSON
            content = """{
              "formUrl": "https://jobs.example.com/apply/1",
              "observedFields": [
                {"fieldKey": "full_name", "label": "Full name", "type": "TEXT", "required": true},
                {"fieldKey": "cv", "label": "Upload your CV", "type": "FILE", "required": true},
                {"fieldKey": "salary", "label": "Desired salary", "type": "TEXT", "required": true},
                {"fieldKey": "start", "label": "When can you start?", "type": "TEXT", "required": false}
              ]
            }"""
        }.andExpect {
            status { isCreated() }
            jsonPath("$.run.status") { value("NEEDS_INPUT") }
            jsonPath("$.run.filledFieldKeys[0]") { value("full_name") }
            jsonPath("$.run.filledFieldKeys[1]") { value("cv") }
            jsonPath("$.run.pendingFieldKeys[0]") { value("salary") }
            jsonPath("$.draft.draft.status") { value("NEEDS_INPUT") }
            jsonPath("$.draft.artifacts[0].type") { value("RESUME_PDF") }
            jsonPath("$.draft.pendingApprovals[0].topic") { value("DESIRED_SALARY") }
            jsonPath("$.draft.pendingApprovals[0].reason") { value("SENSITIVE_TOPIC") }
        }

        mvc.post("/api/v1/application-drafts/$draftId/submit")
            .andExpect {
                status { isConflict() }
                jsonPath("$.code") { value("APPLICATION_NOT_READY") }
            }

        mvc.post("/api/v1/application-drafts/$draftId/answers") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"answers": [
              {"fieldKey": "salary", "value": "28000 PLN per month", "saveToCatalog": true}
            ]}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.draft.status") { value("READY_TO_SUBMIT") }
            // The optional start-date question stays open without blocking the application.
            jsonPath("$.pendingApprovals.length()") { value(1) }
            jsonPath("$.pendingApprovals[0].fieldKey") { value("start") }
            jsonPath("$.pendingApprovals[0].required") { value(false) }
        }

        mvc.post("/api/v1/application-drafts/$draftId/submit")
            .andExpect {
                status { isConflict() }
                jsonPath("$.code") { value("APPLICATION_SUBMIT_NOT_APPROVED") }
            }

        val approval = mvc.post("/api/v1/application-drafts/$draftId/submit-approval")
            .andExpect {
                status { isCreated() }
                jsonPath("$.type") { value("SUBMIT") }
                jsonPath("$.status") { value("PENDING") }
            }
            .andReturn().response.contentAsString
        val approvalId = objectMapper.readTree(approval)["approvalId"].asText()

        mvc.post("/api/v1/application-drafts/$draftId/approvals/$approvalId/decision") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"approved": true, "note": "Checked the rendered PDF"}"""
        }.andExpect { status { isOk() } }

        mvc.post("/api/v1/application-drafts/$draftId/submit") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"reference": "ACME-2026-17"}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.draft.status") { value("SUBMITTED") }
            jsonPath("$.draft.submission.mode") { value("MANUAL") }
            jsonPath("$.draft.submission.reference") { value("ACME-2026-17") }
        }

        val artifactId = fixture.artifactRows.single().artifactId
        val pdf = mvc.get("/api/v1/application-drafts/$draftId/artifacts/$artifactId")
            .andExpect {
                status { isOk() }
                content { contentType(MediaType.APPLICATION_PDF) }
            }
            .andReturn().response.contentAsByteArray
        assertEquals("%PDF-1.7 tailored resume", pdf.decodeToString())
    }

    @Test
    fun `reports an unknown draft and an empty observed form`() {
        mvc.get("/api/v1/application-drafts/${fixture.variantId}")
            .andExpect {
                status { isNotFound() }
                jsonPath("$.code") { value("APPLICATION_DRAFT_NOT_FOUND") }
            }

        val created = mvc.post("/api/v1/vacancies/${fixture.vacancyId}/application-drafts")
            .andReturn().response.contentAsString
        val draftId = objectMapper.readTree(created)["draft"]["draftId"].asText()

        mvc.post("/api/v1/application-drafts/$draftId/runs") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"observedFields": []}"""
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("INVALID_REQUEST") }
        }
    }
}
