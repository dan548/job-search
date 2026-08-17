package th.sibraine.jobagent.candidate.infrastructure

import th.sibraine.jobagent.candidate.domain.*
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayOutputStream

class PdfBoxResumeDocumentParserTest {
    private val parser = PdfBoxResumeDocumentParser()

    @Test
    fun `extracts text skills and unverified fact candidates`() {
        val pdf = pdfWithText("Backend Engineer - Kotlin, Spring Boot, PostgreSQL and Kafka")

        val parsed = parser.parse(ResumeDocument("resume.pdf", pdf))

        assertEquals(1, parsed.pageCount)
        assertTrue(parsed.extractedText.contains("Backend Engineer"))
        assertEquals(listOf("Kotlin", "Spring Boot", "PostgreSQL", "Kafka"), parsed.detectedSkills)
        assertEquals(4, parsed.factCandidates.size)
        assertTrue(parsed.factCandidates.all { !it.verified })
        assertTrue(parsed.factCandidates.all { it.factId.startsWith("resume-") })
        assertEquals(ResumeExtractionMethod.TEXT_LAYER, parsed.extractionMethod)
        assertTrue(parsed.textBlocks.isNotEmpty())
        assertEquals(1, parsed.textBlocks.first().pageNumber)
        assertTrue(parsed.textBlocks.first().boundingBox.width > 0)
        assertEquals(
            parsed.factCandidates.map { it.factId },
            parser.parse(ResumeDocument("renamed.pdf", pdf)).factCandidates.map { it.factId },
        )
        assertEquals(
            parsed.textBlocks.map { it.blockId },
            parser.parse(ResumeDocument("renamed.pdf", pdf)).textBlocks.map { it.blockId },
        )
    }

    @Test
    fun `does not detect short skill inside another word`() {
        val parsed = parser.parse(ResumeDocument("resume.pdf", pdfWithText("MongoDB database")))
        assertTrue("MongoDB" in parsed.detectedSkills)
        assertFalse("Go" in parsed.detectedSkills)
    }

    @Test
    fun `rejects content without PDF signature`() {
        val error = assertThrows<ResumeParsingException> {
            parser.parse(ResumeDocument("resume.pdf", "plain text".toByteArray()))
        }
        assertEquals("INVALID_PDF", error.code)
    }

    @Test
    fun `rejects PDF without text layer`() {
        val output = ByteArrayOutputStream()
        PDDocument().use { document ->
            document.addPage(PDPage())
            document.save(output)
        }
        val error = assertThrows<ResumeParsingException> {
            parser.parse(ResumeDocument("scan.pdf", output.toByteArray()))
        }
        assertEquals("RESUME_TEXT_NOT_FOUND", error.code)
    }

    @Test
    fun `uses OCR fallback when PDF has no text layer`() {
        val output = ByteArrayOutputStream()
        PDDocument().use { document ->
            document.addPage(PDPage())
            document.save(output)
        }
        val ocr = ResumeOcrEngine {
            ResumeOcrResult(
                listOf(
                    ResumeTextBlock(
                        "block-ocr",
                        1,
                        0,
                        "Kotlin backend engineer",
                        ResumeBoundingBox(10.0, 20.0, 200.0, 15.0),
                        0.91,
                    )
                )
            )
        }

        val parsed = PdfBoxResumeDocumentParser(ocr).parse(ResumeDocument("scan.pdf", output.toByteArray()))

        assertEquals(ResumeExtractionMethod.OCR, parsed.extractionMethod)
        assertEquals("Kotlin backend engineer", parsed.extractedText)
        assertEquals(listOf("Kotlin"), parsed.detectedSkills)
        assertTrue(parsed.warnings.any { it.contains("OCR") })
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
