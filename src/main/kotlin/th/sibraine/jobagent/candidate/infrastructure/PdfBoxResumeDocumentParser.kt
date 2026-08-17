package th.sibraine.jobagent.candidate.infrastructure

import th.sibraine.jobagent.candidate.domain.*
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.pdfbox.text.TextPosition
import org.springframework.stereotype.Component
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale

@Component
class PdfBoxResumeDocumentParser(
    private val ocrEngine: ResumeOcrEngine? = null,
) : ResumeDocumentParser {
    override fun parse(document: ResumeDocument): ParsedResume {
        validateInput(document)
        val pdf = try {
            Loader.loadPDF(document.content)
        } catch (error: InvalidPasswordException) {
            throw ResumeParsingException("ENCRYPTED_PDF", "Password-protected PDF files are not supported")
        } catch (error: IOException) {
            throw ResumeParsingException("INVALID_PDF", "The uploaded file is not a readable PDF")
        }

        return pdf.use { parseLoaded(document, it) }
    }

    private fun parseLoaded(document: ResumeDocument, pdf: PDDocument): ParsedResume {
        if (pdf.isEncrypted) {
            throw ResumeParsingException("ENCRYPTED_PDF", "Password-protected PDF files are not supported")
        }
        if (pdf.numberOfPages > MAX_PAGES) {
            throw ResumeParsingException("PDF_PAGE_LIMIT_EXCEEDED", "PDF must contain at most $MAX_PAGES pages")
        }

        val positionalStripper = PositionalTextStripper()
        val textLayer = try {
            normalizeText(positionalStripper.getText(pdf))
        } catch (error: IOException) {
            throw ResumeParsingException("PDF_TEXT_EXTRACTION_FAILED", "Could not extract text from PDF")
        }
        val textBlocks: List<ResumeTextBlock>
        val extractionMethod: ResumeExtractionMethod
        val extractionWarnings: List<String>
        val text: String
        if (textLayer.isNotBlank()) {
            textBlocks = positionalStripper.blocks
            extractionMethod = ResumeExtractionMethod.TEXT_LAYER
            extractionWarnings = emptyList()
            text = textLayer
        } else {
            val ocr = ocrEngine?.extract(document)
            if (ocr == null || ocr.textBlocks.isEmpty()) {
                throw ResumeParsingException(
                    "RESUME_TEXT_NOT_FOUND",
                    "No text layer was found and OCR is unavailable or did not recognize any text",
                )
            }
            textBlocks = ocr.textBlocks
            extractionMethod = ResumeExtractionMethod.OCR
            extractionWarnings = ocr.warnings + "Text was extracted with OCR and may require additional review"
            text = normalizeText(textBlocks.joinToString("\n") { it.text })
        }
        if (text.length > MAX_EXTRACTED_TEXT_LENGTH) {
            throw ResumeParsingException("RESUME_TEXT_LIMIT_EXCEEDED", "Extracted text must not exceed 200,000 characters")
        }

        val lines = text.lineSequence().map(String::trim).filter(String::isNotBlank).toList()
        val skills = TECHNOLOGIES.filter { skill -> containsSkill(text, skill) }
        val facts = skills.map { skill ->
            val evidence = lines.firstOrNull { containsSkill(it, skill) } ?: skill
            CandidateFact(
                factId = stableFactId(skill, evidence),
                type = FactType.SKILL,
                text = evidence.take(MAX_FACT_LENGTH),
                verified = false,
            )
        }

        return ParsedResume(
            fileName = document.fileName,
            pageCount = pdf.numberOfPages,
            extractedText = text,
            detectedSkills = skills,
            factCandidates = facts,
            warnings = listOf(
                "Imported facts are unverified and must be reviewed before saving them to the candidate profile"
            ) + extractionWarnings,
            textBlocks = textBlocks,
            extractionMethod = extractionMethod,
        )
    }

    private fun validateInput(document: ResumeDocument) {
        if (document.content.isEmpty()) {
            throw ResumeParsingException("EMPTY_PDF", "Uploaded PDF is empty")
        }
        if (document.content.size > MAX_FILE_SIZE_BYTES) {
            throw ResumeParsingException("PDF_SIZE_LIMIT_EXCEEDED", "PDF must not exceed 5 MB")
        }
        if (document.content.size < PDF_SIGNATURE.size ||
            !document.content.copyOfRange(0, PDF_SIGNATURE.size).contentEquals(PDF_SIGNATURE)
        ) {
            throw ResumeParsingException("INVALID_PDF", "Uploaded file does not have a valid PDF signature")
        }
    }

    private fun normalizeText(value: String): String = value
        .replace("\u0000", "")
        .replace(Regex("[ \\t]+"), " ")
        .replace(Regex("\\R{3,}"), "\n\n")
        .trim()

    private fun stableFactId(skill: String, evidence: String): String {
        val input = "${skill.lowercase()}|${evidence.lowercase().trim()}".toByteArray()
        val hash = MessageDigest.getInstance("SHA-256").digest(input)
            .take(8).joinToString("") { "%02x".format(it) }
        return "resume-$hash"
    }

    private fun containsSkill(text: String, skill: String): Boolean = Regex(
        "(?i)(?<![\\p{L}\\p{N}])${Regex.escape(skill)}(?![\\p{L}\\p{N}])"
    ).containsMatchIn(text)

    private class PositionalTextStripper : PDFTextStripper() {
        val blocks = mutableListOf<ResumeTextBlock>()
        private var order = 0

        init {
            sortByPosition = true
        }

        override fun writeString(text: String, textPositions: List<TextPosition>) {
            val normalized = text.replace(Regex("\\s+"), " ").trim()
            if (normalized.isNotBlank() && textPositions.isNotEmpty()) {
                val left = textPositions.minOf { it.xDirAdj.toDouble() }
                val top = textPositions.minOf { it.yDirAdj.toDouble() }
                val right = textPositions.maxOf { (it.xDirAdj + it.widthDirAdj).toDouble() }
                val bottom = textPositions.maxOf { (it.yDirAdj + it.heightDir).toDouble() }
                val pageNumber = currentPageNo
                blocks += ResumeTextBlock(
                    blockId = stableBlockId(pageNumber, order, normalized, left, top),
                    pageNumber = pageNumber,
                    order = order++,
                    text = normalized,
                    boundingBox = ResumeBoundingBox(left, top, right - left, bottom - top),
                )
            }
            super.writeString(text, textPositions)
        }

        private fun stableBlockId(pageNumber: Int, order: Int, text: String, x: Double, y: Double): String {
            val input = "$pageNumber|$order|${"%.2f".format(Locale.ROOT, x)}|" +
                "${"%.2f".format(Locale.ROOT, y)}|$text"
            val hash = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
                .take(8).joinToString("") { "%02x".format(it) }
            return "block-$hash"
        }
    }

    companion object {
        private const val MAX_FILE_SIZE_BYTES = 5 * 1024 * 1024
        private const val MAX_PAGES = 20
        private const val MAX_FACT_LENGTH = 500
        private const val MAX_EXTRACTED_TEXT_LENGTH = 200_000
        private val PDF_SIGNATURE = "%PDF-".toByteArray()
        private val TECHNOLOGIES = listOf(
            "Java", "Kotlin", "Spring Boot", "Ktor", "PostgreSQL", "Kafka", "Docker",
            "Kubernetes", "Testcontainers", "AWS", "REST API", "Redis", "Python", "Go",
            "React", "TypeScript", "JavaScript", "C#", ".NET", "SQL", "MongoDB", "RabbitMQ",
            "Terraform", "Azure", "GCP",
        )
    }
}
