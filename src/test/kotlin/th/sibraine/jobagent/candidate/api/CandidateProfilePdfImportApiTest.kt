package th.sibraine.jobagent.candidate.api

import th.sibraine.jobagent.candidate.application.CandidateProfileService
import th.sibraine.jobagent.candidate.application.ParseResumeUseCase
import th.sibraine.jobagent.candidate.infrastructure.PdfBoxResumeDocumentParser
import th.sibraine.jobagent.shared.ApiExceptionHandler
import io.mockk.mockk
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.multipart
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.io.ByteArrayOutputStream

class CandidateProfilePdfImportApiTest {
    private val mvc: MockMvc = MockMvcBuilders.standaloneSetup(
        CandidateProfileController(
            mockk<CandidateProfileService>(),
            ParseResumeUseCase(PdfBoxResumeDocumentParser()),
        )
    ).setControllerAdvice(ApiExceptionHandler()).build()

    @Test
    fun `uploads PDF and returns parsed draft without verified facts`() {
        val file = MockMultipartFile("file", "resume.pdf", "application/pdf", pdfWithText("Kotlin and PostgreSQL"))

        mvc.multipart("/api/v1/candidate-profile/import/pdf") { file(file) }
            .andExpect {
                status { isOk() }
                jsonPath("$.fileName") { value("resume.pdf") }
                jsonPath("$.pageCount") { value(1) }
                jsonPath("$.detectedSkills[0]") { value("Kotlin") }
                jsonPath("$.factCandidates[0].verified") { value(false) }
            }
    }

    @Test
    fun `invalid file returns typed API error`() {
        val file = MockMultipartFile("file", "resume.pdf", "application/pdf", "not pdf".toByteArray())

        mvc.multipart("/api/v1/candidate-profile/import/pdf") { file(file) }
            .andExpect {
                status { isUnprocessableEntity() }
                jsonPath("$.code") { value("INVALID_PDF") }
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
