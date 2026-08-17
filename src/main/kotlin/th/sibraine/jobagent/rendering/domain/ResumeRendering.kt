package th.sibraine.jobagent.rendering.domain

import th.sibraine.jobagent.tailoring.domain.ResumeVariant

data class RenderedResumePdf(
    val bytes: ByteArray,
    val pageCount: Int,
    val extractedText: String,
    val clickableLinkCount: Int,
)

interface ResumePdfRenderer {
    fun render(variant: ResumeVariant): RenderedResumePdf
}

class ResumeRenderingException(
    val code: String,
    override val message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
