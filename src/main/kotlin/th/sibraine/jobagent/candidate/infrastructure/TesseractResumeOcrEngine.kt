package th.sibraine.jobagent.candidate.infrastructure

import th.sibraine.jobagent.candidate.domain.*
import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.PDFRenderer
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

@ConfigurationProperties("job-agent.resume.ocr")
data class TesseractOcrProperties(
    val enabled: Boolean = false,
    val command: String = "tesseract",
    val languages: String = "eng+rus",
    val dpi: Int = 300,
    val timeoutSeconds: Long = 45,
)

@Configuration
@EnableConfigurationProperties(TesseractOcrProperties::class)
class ResumeOcrConfiguration

@Component
@ConditionalOnProperty(prefix = "job-agent.resume.ocr", name = ["enabled"], havingValue = "true")
class TesseractResumeOcrEngine(private val properties: TesseractOcrProperties) : ResumeOcrEngine {
    init {
        require(properties.command.isNotBlank()) { "OCR command must not be blank" }
        require(properties.languages.isNotBlank()) { "OCR languages must not be blank" }
        require(properties.dpi in 150..600) { "OCR DPI must be between 150 and 600" }
        require(properties.timeoutSeconds in 1..300) { "OCR timeout must be between 1 and 300 seconds" }
    }

    override fun extract(document: ResumeDocument): ResumeOcrResult {
        val tempDir = Files.createTempDirectory("jobagent-resume-ocr-")
        try {
            val blocks = Loader.loadPDF(document.content).use { pdf ->
                val renderer = PDFRenderer(pdf)
                buildList {
                    repeat(pdf.numberOfPages) { pageIndex ->
                        val pageNumber = pageIndex + 1
                        val imagePath = tempDir.resolve("page-$pageNumber.png")
                        val tsvPath = tempDir.resolve("page-$pageNumber.tsv")
                        val errorPath = tempDir.resolve("page-$pageNumber.err")
                        val image = renderer.renderImageWithDPI(pageIndex, properties.dpi.toFloat())
                        if (!ImageIO.write(image, "png", imagePath.toFile())) {
                            throw ResumeParsingException("OCR_RENDER_FAILED", "Could not render PDF page for OCR")
                        }
                        runTesseract(imagePath, tsvPath, errorPath)
                        addAll(parseTsv(Files.readString(tsvPath), pageNumber, size))
                    }
                }
            }
            return ResumeOcrResult(
                textBlocks = blocks,
                warnings = listOf("OCR engine: Tesseract (${properties.languages})"),
            )
        } catch (error: ResumeParsingException) {
            throw error
        } catch (error: Exception) {
            throw ResumeParsingException("OCR_FAILED", "Could not extract text from scanned PDF")
        } finally {
            deleteTemporaryDirectory(tempDir)
        }
    }

    internal fun parseTsv(tsv: String, pageNumber: Int, startingOrder: Int = 0): List<ResumeTextBlock> {
        data class Word(
            val block: Int,
            val paragraph: Int,
            val line: Int,
            val left: Int,
            val top: Int,
            val width: Int,
            val height: Int,
            val confidence: Double,
            val text: String,
        )

        val words = tsv.lineSequence().drop(1).mapNotNull { row ->
            val columns = row.split('\t', limit = 12)
            if (columns.size < 12 || columns[0].toIntOrNull() != 5) return@mapNotNull null
            val text = columns[11].trim()
            if (text.isBlank()) return@mapNotNull null
            Word(
                block = columns[2].toIntOrNull() ?: return@mapNotNull null,
                paragraph = columns[3].toIntOrNull() ?: return@mapNotNull null,
                line = columns[4].toIntOrNull() ?: return@mapNotNull null,
                left = columns[6].toIntOrNull() ?: return@mapNotNull null,
                top = columns[7].toIntOrNull() ?: return@mapNotNull null,
                width = columns[8].toIntOrNull() ?: return@mapNotNull null,
                height = columns[9].toIntOrNull() ?: return@mapNotNull null,
                confidence = columns[10].toDoubleOrNull() ?: 0.0,
                text = text,
            )
        }.toList()

        var order = startingOrder
        return words.groupBy { Triple(it.block, it.paragraph, it.line) }.values.map { line ->
            val sorted = line.sortedBy { it.left }
            val left = sorted.minOf { it.left }.toDouble()
            val top = sorted.minOf { it.top }.toDouble()
            val right = sorted.maxOf { it.left + it.width }.toDouble()
            val bottom = sorted.maxOf { it.top + it.height }.toDouble()
            val text = sorted.joinToString(" ") { it.text }
            val currentOrder = order++
            ResumeTextBlock(
                blockId = stableBlockId(pageNumber, currentOrder, text, left, top),
                pageNumber = pageNumber,
                order = currentOrder,
                text = text,
                boundingBox = ResumeBoundingBox(left, top, right - left, bottom - top),
                confidence = sorted.map { it.confidence }.filter { it >= 0 }.average()
                    .takeUnless(Double::isNaN)?.div(100.0)?.coerceIn(0.0, 1.0),
                coordinateSpace = ResumeCoordinateSpace.IMAGE_PIXELS,
            )
        }
    }

    private fun runTesseract(imagePath: Path, tsvPath: Path, errorPath: Path) {
        val process = try {
            ProcessBuilder(
                properties.command,
                imagePath.toString(),
                "stdout",
                "-l",
                properties.languages,
                "--psm",
                "6",
                "tsv",
            )
                .redirectOutput(tsvPath.toFile())
                .redirectError(errorPath.toFile())
                .start()
        } catch (error: Exception) {
            throw ResumeParsingException("OCR_UNAVAILABLE", "Configured Tesseract command is unavailable")
        }
        if (!process.waitFor(properties.timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw ResumeParsingException("OCR_TIMEOUT", "OCR timed out while processing a PDF page")
        }
        if (process.exitValue() != 0) {
            throw ResumeParsingException("OCR_FAILED", "Tesseract could not process a PDF page")
        }
    }

    private fun stableBlockId(pageNumber: Int, order: Int, text: String, x: Double, y: Double): String {
        val input = "ocr|$pageNumber|$order|$x|$y|$text".toByteArray()
        val hash = MessageDigest.getInstance("SHA-256").digest(input)
            .take(8).joinToString("") { "%02x".format(it) }
        return "block-$hash"
    }

    private fun deleteTemporaryDirectory(directory: Path) {
        if (!Files.exists(directory)) return
        Files.walk(directory).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }
}
