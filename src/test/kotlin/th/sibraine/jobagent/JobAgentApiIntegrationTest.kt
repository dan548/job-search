package th.sibraine.jobagent

import th.sibraine.jobagent.candidate.domain.ResumeImportStatus
import th.sibraine.jobagent.candidate.infrastructure.ResumeImportJpaRepository
import th.sibraine.jobagent.matching.infrastructure.MatchResultJpaRepository
import th.sibraine.jobagent.matching.infrastructure.VacancyAnalysisJpaRepository
import th.sibraine.jobagent.tailoring.infrastructure.ResumeVariantJpaRepository
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.hamcrest.Matchers.greaterThan
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.multipart
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import org.springframework.mock.web.MockMultipartFile
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.UUID

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class JobAgentApiIntegrationTest @Autowired constructor(
    private val mvc: MockMvc,
    private val objectMapper: ObjectMapper,
    private val analyses: VacancyAnalysisJpaRepository,
    private val matches: MatchResultJpaRepository,
    private val resumeImports: ResumeImportJpaRepository,
    private val variants: ResumeVariantJpaRepository,
) {
    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:17-alpine")
    }

    @Test
    fun `complete analysis persists and can be retrieved`() {
        mvc.put("/api/v1/candidate-profile") {
            contentType = MediaType.APPLICATION_JSON
            content = """{
              "generalInfo":{"displayName":"Test Candidate","headline":"Backend Engineer"},
              "skills":["Kotlin","Spring Boot","PostgreSQL"],
              "facts":[
                {"factId":"fact-001","type":"SKILL","text":"Built services with Kotlin and Spring Boot","verified":true},
                {"factId":"fact-002","type":"SKILL","text":"Used PostgreSQL in backend systems","verified":true}
              ]
            }"""
        }.andExpect { status { isOk() } }

        val vacancyResponse = mvc.post("/api/v1/vacancies") {
            contentType = MediaType.APPLICATION_JSON
            content = """{
              "source":"MANUAL","externalId":"test-001","url":"https://example.com/jobs/1",
              "company":"Example","title":"Senior Kotlin Backend Engineer",
              "description":"Required: Kotlin, Spring Boot and PostgreSQL. Nice to have: Kafka.",
              "location":"Remote"
            }"""
        }.andExpect { status { isCreated() } }.andReturn().response.contentAsString
        val vacancyId = UUID.fromString(objectMapper.readTree(vacancyResponse)["id"].asText())

        mvc.post("/api/v1/vacancies/$vacancyId/analyze")
            .andExpect {
                status { isOk() }
                jsonPath("$.analysis.requiredSkills.length()", greaterThan(0))
                jsonPath("$.match.score") { isNumber() }
                jsonPath("$.match.matchedRequirements[0].evidenceFactIds[0]") { exists() }
                jsonPath("$.match.requirementEvidenceMatrix[0].status") { value("MATCHED") }
                jsonPath("$.match.requirementEvidenceMatrix[0].evidence[0].text") { exists() }
            }

        assertNotNull(analyses.findByVacancyId(vacancyId))
        assertNotNull(matches.findFirstByVacancyIdOrderByCreatedAtDesc(vacancyId))

        val saved = mvc.get("/api/v1/vacancies/$vacancyId/analysis")
            .andExpect { status { isOk() } }.andReturn().response.contentAsString
        val json: JsonNode = objectMapper.readTree(saved)
        assertNotNull(json["analysis"])
        assertNotNull(json["match"])
    }

    @Test
    fun `resume preview and confirmed snapshot persist in PostgreSQL`() {
        val pdf = pdfWithText("Backend Engineer with Kotlin")
        val file = MockMultipartFile("file", "resume.pdf", "application/pdf", pdf)

        val previewBody = mvc.multipart("/api/v1/candidate-profile/resume-imports") { file(file) }
            .andExpect {
                status { isCreated() }
                jsonPath("$.version") { isNumber() }
                jsonPath("$.status") { value("PREVIEW") }
                jsonPath("$.extractionMethod") { value("TEXT_LAYER") }
                jsonPath("$.textBlocks[0].pageNumber") { value(1) }
                jsonPath("$.structuredResume.skills[0].name") { value("Kotlin") }
            }
            .andReturn().response.contentAsString
        val preview = objectMapper.readTree(previewBody)
        val importId = UUID.fromString(preview["importId"].asText())

        mvc.get("/api/v1/candidate-profile/resume-imports/$importId/diff")
            .andExpect {
                status { isOk() }
                jsonPath("$.targetVersion") { value(preview["version"].asLong()) }
                jsonPath("$.changes[0].changeType") { value("ADDED") }
            }

        mvc.post("/api/v1/candidate-profile/resume-imports/$importId/confirm") {
            contentType = MediaType.APPLICATION_JSON
            content = """{
              "structuredResume": {
                "schemaVersion": 1,
                "skills": [{
                  "elementId": "skill-kotlin",
                  "name": "Kotlin",
                  "metadata": {"reviewStatus": "CONFIRMED"}
                }]
              }
            }"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.status") { value("CONFIRMED") }
        }

        val saved = resumeImports.findByImportId(importId)
        assertNotNull(saved)
        assertEquals(ResumeImportStatus.CONFIRMED, saved!!.status)
        assertTrue(saved.sourcePdf.contentEquals(pdf))
        assertTrue(saved.textBlocks.isNotEmpty())
        assertEquals("Kotlin", saved.structuredResume.skills.single().name)

        mvc.get("/api/v1/candidate-profile/resume-imports/confirmed/latest")
            .andExpect {
                status { isOk() }
                jsonPath("$.importId") { value(importId.toString()) }
            }
    }

    @Test
    fun `tailored resume variant is evidence-based and persists`() {
        mvc.put("/api/v1/candidate-profile") {
            contentType = MediaType.APPLICATION_JSON
            content = """{
              "generalInfo":{"displayName":"Test Candidate","headline":"Backend Engineer"},
              "skills":["Kotlin"],
              "facts":[
                {"factId":"fact-001","type":"SKILL","text":"Built services with Kotlin","verified":true}
              ]
            }"""
        }.andExpect { status { isOk() } }

        val vacancyResponse = mvc.post("/api/v1/vacancies") {
            contentType = MediaType.APPLICATION_JSON
            content = """{
              "source":"MANUAL","externalId":"tailor-001","company":"Example",
              "title":"Senior Kotlin Backend Engineer",
              "description":"Required: Kotlin. Nice to have: Kafka.","location":"Remote"
            }"""
        }.andExpect { status { isCreated() } }.andReturn().response.contentAsString
        val vacancyId = UUID.fromString(objectMapper.readTree(vacancyResponse)["id"].asText())
        mvc.post("/api/v1/vacancies/$vacancyId/analyze").andExpect { status { isOk() } }

        val importBody = mvc.multipart("/api/v1/candidate-profile/resume-imports") {
            file(MockMultipartFile("file", "resume.pdf", "application/pdf", pdfWithText("Backend Engineer with Kotlin")))
        }.andExpect { status { isCreated() } }.andReturn().response.contentAsString
        val importId = UUID.fromString(objectMapper.readTree(importBody)["importId"].asText())
        val skillElementId = objectMapper.readTree(importBody)["structuredResume"]["skills"][0]["elementId"].asText()

        mvc.post("/api/v1/candidate-profile/resume-imports/$importId/confirm") {
            contentType = MediaType.APPLICATION_JSON
            content = """{
              "structuredResume": {
                "schemaVersion": 1,
                "skills": [{
                  "elementId": "$skillElementId",
                  "name": "Kotlin",
                  "metadata": {"reviewStatus": "CONFIRMED"}
                }]
              }
            }"""
        }.andExpect { status { isOk() } }

        val variantBody = mvc.post("/api/v1/vacancies/$vacancyId/resume-variants")
            .andExpect {
                status { isCreated() }
                jsonPath("$.version") { isNumber() }
                jsonPath("$.templateId") { value("ats-single-column") }
                jsonPath("$.baseImportId") { value(importId.toString()) }
                jsonPath("$.resume.skills[0].elementId") { value(skillElementId) }
                jsonPath("$.plan.gaps[0].requirement") { exists() }
            }
            .andReturn().response.contentAsString
        val variantId = UUID.fromString(objectMapper.readTree(variantBody)["variantId"].asText())

        assertNotNull(variants.findByVariantId(variantId))
        mvc.get("/api/v1/vacancies/$vacancyId/resume-variants/latest")
            .andExpect {
                status { isOk() }
                jsonPath("$.variantId") { value(variantId.toString()) }
            }
        mvc.get("/api/v1/resume-variants/$variantId").andExpect { status { isOk() } }

        mvc.post("/api/v1/resume-variants/$variantId/cover-letter")
            .andExpect {
                status { isOk() }
                jsonPath("$.text") { isNotEmpty() }
                jsonPath("$.generatedAt") { exists() }
            }
        assertNotNull(variants.findByVariantId(variantId)?.coverLetterText)
    }

    @Test
    fun `personal MVP happy path completes from PDF import to a recorded submission`() {
        val suffix = UUID.randomUUID().toString().take(8)
        val identityBody = mvc.post("/api/v1/candidate-identities") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"label":"E2E $suffix","displayName":"Ada E2E"}"""
        }.andExpect { status { isCreated() } }.andReturn().response.contentAsString
        val profileId = objectMapper.readTree(identityBody)["id"].asText()

        mvc.post("/api/v1/candidate-profile/contacts") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"type":"EMAIL","value":"ada-$suffix@example.com","label":"E2E"}"""
        }.andExpect { status { isCreated() } }

        val previewBody = mvc.multipart("/api/v1/candidate-profile/resume-imports") {
            file(MockMultipartFile(
                "file", "resume-$suffix.pdf", "application/pdf",
                pdfWithText("Backend Engineer with Kotlin Spring Boot and PostgreSQL"),
            ))
        }.andExpect {
            status { isCreated() }
            jsonPath("$.status") { value("PREVIEW") }
            jsonPath("$.structuredResume.skills.length()", greaterThan(0))
        }.andReturn().response.contentAsString
        val preview = objectMapper.readTree(previewBody)
        val importId = preview["importId"].asText()
        val reviewedSkills = preview["structuredResume"]["skills"].map { skill ->
            mapOf(
                "elementId" to skill["elementId"].asText(),
                "name" to skill["name"].asText(),
                "category" to skill["category"]?.takeUnless(JsonNode::isNull)?.asText(),
                "metadata" to mapOf("reviewStatus" to "CONFIRMED"),
            )
        }
        mvc.post("/api/v1/candidate-profile/resume-imports/$importId/confirm") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(mapOf(
                "structuredResume" to mapOf("schemaVersion" to 1, "skills" to reviewedSkills),
                "mode" to "ENRICH",
            ))
        }.andExpect {
            status { isOk() }
            jsonPath("$.status") { value("CONFIRMED") }
        }

        val vacancyBody = mvc.post("/api/v1/vacancies") {
            contentType = MediaType.APPLICATION_JSON
            content = """{
              "source":"MANUAL","externalId":"e2e-$suffix","url":"https://jobs.example.com/$suffix",
              "company":"E2E Company","title":"Kotlin Backend Engineer",
              "description":"Required: Kotlin, Spring Boot and PostgreSQL.","location":"Remote"
            }"""
        }.andExpect { status { isCreated() } }.andReturn().response.contentAsString
        val vacancyId = objectMapper.readTree(vacancyBody)["id"].asText()
        mvc.post("/api/v1/vacancies/$vacancyId/analyze").andExpect {
            status { isOk() }
            jsonPath("$.match.score") { isNumber() }
        }

        val variantBody = mvc.post("/api/v1/vacancies/$vacancyId/resume-variants")
            .andExpect {
                status { isCreated() }
                jsonPath("$.candidateProfileId") { value(profileId) }
                jsonPath("$.reviewedAt") { doesNotExist() }
            }.andReturn().response.contentAsString
        val variantId = objectMapper.readTree(variantBody)["variantId"].asText()
        mvc.post("/api/v1/resume-variants/$variantId/review-approval").andExpect {
            status { isOk() }
            jsonPath("$.reviewedAt") { exists() }
        }
        mvc.get("/api/v1/resume-variants/$variantId/pdf").andExpect {
            status { isOk() }
            header { string("X-Resume-ATS-Check", "passed") }
            header { exists("X-Resume-Page-Count") }
            content { contentType(MediaType.APPLICATION_PDF) }
        }

        mvc.put("/api/v1/application-settings") {
            contentType = MediaType.APPLICATION_JSON
            content = """{
              "desiredSalary":{"amount":120000,"currency":"USD","period":"YEAR","negotiable":true},
              "requiresSponsorship":false,"remotePreference":"Remote","noticePeriod":"30 days"
            }"""
        }.andExpect { status { isOk() } }

        val draftBody = mvc.post("/api/v1/vacancies/$vacancyId/application-drafts") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"resumeVariantId":"$variantId"}"""
        }.andExpect { status { isCreated() } }.andReturn().response.contentAsString
        val draftId = objectMapper.readTree(draftBody)["draft"]["draftId"].asText()
        mvc.post("/api/v1/application-drafts/$draftId/runs") {
            contentType = MediaType.APPLICATION_JSON
            content = """{
              "formUrl":"https://jobs.example.com/$suffix/apply","idempotencyKey":"e2e-$suffix",
              "observedFields":[
                {"fieldKey":"full_name","label":"Full name","required":true},
                {"fieldKey":"email","label":"Email","type":"EMAIL","required":true},
                {"fieldKey":"salary","label":"Desired salary","required":true}
              ]
            }"""
        }.andExpect {
            status { isCreated() }
            jsonPath("$.draft.draft.status") { value("READY_TO_SUBMIT") }
            jsonPath("$.draft.draft.answers.length()") { value(3) }
            jsonPath("$.draft.artifacts[0].type") { value("RESUME_PDF") }
        }

        val approvalBody = mvc.post("/api/v1/application-drafts/$draftId/submit-approval")
            .andExpect { status { isCreated() } }.andReturn().response.contentAsString
        val approvalId = objectMapper.readTree(approvalBody)["approvalId"].asText()
        mvc.post("/api/v1/application-drafts/$draftId/approvals/$approvalId/decision") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"approved":true,"note":"E2E final review completed"}"""
        }.andExpect { status { isOk() } }
        val submittedAt = Instant.now()
        mvc.post("/api/v1/application-drafts/$draftId/manual-submission") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(mapOf(
                "submittedAt" to submittedAt,
                "reference" to "E2E-$suffix",
                "note" to "Automated personal MVP happy path",
            ))
        }.andExpect {
            status { isOk() }
            jsonPath("$.draft.status") { value("SUBMITTED") }
            jsonPath("$.draft.submission.mode") { value("MANUAL") }
            jsonPath("$.draft.submission.reference") { value("E2E-$suffix") }
            jsonPath("$.pendingApprovals.length()") { value(0) }
        }

        mvc.get("/api/v1/vacancies/$vacancyId/application-drafts/latest").andExpect {
            status { isOk() }
            jsonPath("$.draft.status") { value("SUBMITTED") }
            jsonPath("$.draft.resumeVariantId") { value(variantId) }
        }
    }

    private fun pdfWithText(text: String): ByteArray {
        val output = ByteArrayOutputStream()
        PDDocument().use { document ->
            val page = PDPage()
            document.addPage(page)
            PDPageContentStream(document, page).use { stream ->
                stream.beginText()
                stream.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 12f)
                stream.newLineAtOffset(50f, 750f)
                stream.showText(text)
                stream.endText()
            }
            document.save(output)
        }
        return output.toByteArray()
    }
}
