package th.sibraine.jobagent.rendering.infrastructure

import th.sibraine.jobagent.rendering.domain.RenderedResumePdf
import th.sibraine.jobagent.rendering.domain.ResumeRenderingException
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import org.apache.pdfbox.rendering.PDFRenderer
import org.apache.pdfbox.text.PDFTextStripper
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.awt.image.BufferedImage
import java.text.Normalizer

@Component
class RenderedPdfValidator(
    @Value("\${job-agent.resume.rendering.max-pages:3}") private val maxPages: Int,
) {
    init {
        require(maxPages > 0) { "Resume page limit must be positive" }
    }

    fun validate(pdf: ByteArray, expectedTextBlocks: List<String>, expectedLinkCount: Int): RenderedResumePdf {
        Loader.loadPDF(pdf).use { document ->
            requireValidPageCount(document)
            val extractedText = PDFTextStripper().getText(document)
            requireAtsReadable(extractedText, expectedTextBlocks)
            requireEmbeddedFonts(document)
            requireVisuallySafePages(document)
            val linkCount = document.pages.sumOf { page -> page.annotations.count { it is PDAnnotationLink } }
            if (linkCount < expectedLinkCount) {
                fail("RESUME_LINK_CHECK_FAILED", "Rendered PDF is missing clickable links")
            }
            return RenderedResumePdf(pdf, document.numberOfPages, extractedText.trim(), linkCount)
        }
    }

    private fun requireValidPageCount(document: PDDocument) {
        if (document.numberOfPages == 0) fail("RESUME_PDF_EMPTY", "Rendered PDF has no pages")
        if (document.numberOfPages > maxPages) {
            fail("RESUME_PAGE_LIMIT_EXCEEDED", "Rendered resume exceeds the $maxPages page limit")
        }
    }

    private fun requireAtsReadable(extractedText: String, expectedTextBlocks: List<String>) {
        val haystack = normalize(extractedText)
        val missing = expectedTextBlocks.filter { normalize(it).let { block -> block.isNotEmpty() && block !in haystack } }
        if (missing.isNotEmpty()) {
            fail(
                "RESUME_ATS_CHECK_FAILED",
                "Rendered PDF lost ${missing.size} text block(s) during ATS extraction",
            )
        }
    }

    private fun requireEmbeddedFonts(document: PDDocument) {
        val fonts = document.pages.flatMap { page ->
            page.resources.fontNames.mapNotNull { name -> runCatching { page.resources.getFont(name) }.getOrNull() }
        }
        if (fonts.isEmpty() || fonts.any { !it.isEmbedded }) {
            fail("RESUME_FONT_CHECK_FAILED", "Rendered PDF must use embedded fonts only")
        }
    }

    private fun requireVisuallySafePages(document: PDDocument) {
        val renderer = PDFRenderer(document)
        repeat(document.numberOfPages) { pageIndex ->
            val image = renderer.renderImageWithDPI(pageIndex, 96f)
            if (!containsInk(image)) fail("RESUME_VISUAL_CHECK_FAILED", "Rendered PDF contains a blank page")
            if (edgeContainsInk(image)) fail("RESUME_VISUAL_CHECK_FAILED", "Rendered PDF content reaches the page edge")
        }
    }

    private fun containsInk(image: BufferedImage): Boolean {
        var ink = 0
        for (y in 0 until image.height step 3) for (x in 0 until image.width step 3) {
            if (!isWhite(image.getRGB(x, y)) && ++ink >= 20) return true
        }
        return false
    }

    private fun edgeContainsInk(image: BufferedImage): Boolean {
        val band = 3
        for (x in 0 until image.width) for (y in 0 until band) {
            if (!isWhite(image.getRGB(x, y)) || !isWhite(image.getRGB(x, image.height - 1 - y))) return true
        }
        for (y in 0 until image.height) for (x in 0 until band) {
            if (!isWhite(image.getRGB(x, y)) || !isWhite(image.getRGB(image.width - 1 - x, y))) return true
        }
        return false
    }

    private fun isWhite(rgb: Int): Boolean {
        val red = rgb shr 16 and 0xff
        val green = rgb shr 8 and 0xff
        val blue = rgb and 0xff
        return red >= 248 && green >= 248 && blue >= 248
    }

    private fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .lowercase()
        .replace(Regex("[^\\p{L}\\p{N}+#]+"), " ")
        .trim()
        .replace(Regex("\\s+"), " ")

    private fun fail(code: String, message: String): Nothing = throw ResumeRenderingException(code, message)
}
