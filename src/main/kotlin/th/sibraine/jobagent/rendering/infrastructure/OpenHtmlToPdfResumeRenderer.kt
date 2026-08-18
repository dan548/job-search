package th.sibraine.jobagent.rendering.infrastructure

import com.openhtmltopdf.extend.FSSupplier
import com.openhtmltopdf.outputdevice.helper.BaseRendererBuilder.FontStyle
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder
import th.sibraine.jobagent.rendering.domain.RenderedResumePdf
import th.sibraine.jobagent.rendering.domain.ResumePdfRenderer
import th.sibraine.jobagent.rendering.domain.ResumeRenderingException
import th.sibraine.jobagent.tailoring.domain.CURRENT_TEMPLATE_ID
import th.sibraine.jobagent.tailoring.domain.CURRENT_TEMPLATE_VERSION
import th.sibraine.jobagent.tailoring.domain.ResumeVariant
import org.springframework.stereotype.Component
import java.io.ByteArrayOutputStream
import java.io.InputStream
import org.apache.pdfbox.Loader

@Component
class OpenHtmlToPdfResumeRenderer(
    private val htmlRenderer: AtsSingleColumnHtmlRenderer,
    private val validator: RenderedPdfValidator,
) : ResumePdfRenderer {
    override fun render(variant: ResumeVariant): RenderedResumePdf {
        if (variant.templateId != CURRENT_TEMPLATE_ID || variant.templateVersion != CURRENT_TEMPLATE_VERSION) {
            throw ResumeRenderingException("UNSUPPORTED_RESUME_TEMPLATE", "Resume template is not supported")
        }
        val document = htmlRenderer.render(variant.resume)
        return try {
            val regularPdf = renderPdf(document.html)
            val regularPages = pageCount(regularPdf)
            val compactDocument = if (regularPages > 1) htmlRenderer.render(variant.resume, compact = true) else null
            val compactPdf = compactDocument?.let { renderPdf(it.html) }
            val useCompact = compactPdf != null && pageCount(compactPdf) < regularPages
            val selectedDocument = if (useCompact) compactDocument!! else document
            val selectedPdf = if (useCompact) compactPdf!! else regularPdf
            validator.validate(selectedPdf, selectedDocument.expectedTextBlocks, selectedDocument.expectedLinkCount)
        } catch (error: ResumeRenderingException) {
            throw error
        } catch (error: Exception) {
            throw ResumeRenderingException("RESUME_RENDERING_FAILED", "Resume PDF could not be rendered", error)
        }
    }

    private fun renderPdf(html: String): ByteArray {
        val output = ByteArrayOutputStream()
        PdfRendererBuilder()
            .withHtmlContent(html, null)
            .withProducer("Job Agent CV Renderer")
            .useFont(font(REGULAR_FONT), FONT_FAMILY, 400, FontStyle.NORMAL, true)
            .useFont(font(BOLD_FONT), FONT_FAMILY, 700, FontStyle.NORMAL, true)
            .useFont(font(ITALIC_FONT), FONT_FAMILY, 400, FontStyle.ITALIC, true)
            .useFont(font(BOLD_ITALIC_FONT), FONT_FAMILY, 700, FontStyle.ITALIC, true)
            .toStream(output)
            .run()
        return output.toByteArray()
    }

    private fun pageCount(pdf: ByteArray): Int = Loader.loadPDF(pdf).use { it.numberOfPages }

    private fun font(path: String) = FSSupplier<InputStream> {
        javaClass.classLoader.getResourceAsStream(path)
            ?: throw ResumeRenderingException("RESUME_FONT_NOT_FOUND", "Embedded resume font is not available")
    }

    companion object {
        private const val FONT_FAMILY = "Liberation Sans"
        private const val FONT_ROOT = "com/mpobjects/jasperreports/fonts/liberation"
        private const val REGULAR_FONT = "$FONT_ROOT/LiberationSans-Regular.ttf"
        private const val BOLD_FONT = "$FONT_ROOT/LiberationSans-Bold.ttf"
        private const val ITALIC_FONT = "$FONT_ROOT/LiberationSans-Italic.ttf"
        private const val BOLD_ITALIC_FONT = "$FONT_ROOT/LiberationSans-BoldItalic.ttf"
    }
}
