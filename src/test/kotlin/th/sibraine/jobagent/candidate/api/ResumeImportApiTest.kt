package th.sibraine.jobagent.candidate.api

import th.sibraine.jobagent.candidate.application.ResumeImportService
import th.sibraine.jobagent.candidate.application.CandidateProfileService
import th.sibraine.jobagent.candidate.domain.StructuredResumeValidator
import th.sibraine.jobagent.candidate.infrastructure.PdfBoxResumeDocumentParser
import th.sibraine.jobagent.candidate.infrastructure.DeterministicResumeDecomposer
import th.sibraine.jobagent.candidate.infrastructure.ResumeImportEntity
import th.sibraine.jobagent.candidate.infrastructure.ResumeImportJpaRepository
import th.sibraine.jobagent.shared.ApiExceptionHandler
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.mockk.every
import io.mockk.mockk
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.multipart
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.io.ByteArrayOutputStream
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class ResumeImportApiTest {
    private val imports = mockk<ResumeImportJpaRepository>()
    private val objectMapper: ObjectMapper = jacksonObjectMapper().findAndRegisterModules()
    private val service = ResumeImportService(
        PdfBoxResumeDocumentParser(),
        DeterministicResumeDecomposer(),
        imports,
        StructuredResumeValidator(),
        mockk<CandidateProfileService>(relaxed = true),
        Clock.fixed(Instant.EPOCH, ZoneOffset.UTC),
    )
    private val mvc: MockMvc = MockMvcBuilders.standaloneSetup(ResumeImportController(service))
        .setControllerAdvice(ApiExceptionHandler())
        .build()

    @Test
    fun `creates preview and confirms edited structured resume`() {
        var saved: ResumeImportEntity? = null
        every { imports.save(any()) } answers {
            firstArg<ResumeImportEntity>().also { entity ->
                entity.version = 1
                saved = entity
            }
        }
        every { imports.findByImportId(any()) } answers { saved }
        every { imports.findFirstByCandidateProfileIdAndStatusOrderByVersionDesc(any(), any()) } returns null
        val file = MockMultipartFile("file", "resume.pdf", "application/pdf", pdfWithText("Kotlin"))

        val previewBody = mvc.multipart("/api/v1/candidate-profile/resume-imports") { file(file) }
            .andExpect {
                status { isCreated() }
                jsonPath("$.version") { value(1) }
                jsonPath("$.status") { value("PREVIEW") }
                jsonPath("$.extractionMethod") { value("TEXT_LAYER") }
                jsonPath("$.textBlocks[0].boundingBox.width") { isNumber() }
                jsonPath("$.structuredResume.skills[0].name") { value("Kotlin") }
                jsonPath("$.structuredResume.skills[0].metadata.reviewStatus") { value("UNREVIEWED") }
            }
            .andReturn().response.contentAsString

        val preview = objectMapper.readTree(previewBody)
        val importId = preview["importId"].asText()

        mvc.get("/api/v1/candidate-profile/resume-imports/$importId/diff")
            .andExpect {
                status { isOk() }
                jsonPath("$.baseVersion") { doesNotExist() }
                jsonPath("$.changes[0].changeType") { value("ADDED") }
            }
        val confirmBody = """{
          "structuredResume": {
            "schemaVersion": 1,
            "skills": [{
              "elementId": "resume-kotlin",
              "name": "Kotlin",
              "metadata": {"reviewStatus": "CONFIRMED"}
            }]
          }
        }"""

        mvc.post("/api/v1/candidate-profile/resume-imports/$importId/confirm") {
            contentType = MediaType.APPLICATION_JSON
            content = confirmBody
        }.andExpect {
            status { isOk() }
            jsonPath("$.status") { value("CONFIRMED") }
            jsonPath("$.confirmedAt") { exists() }
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
