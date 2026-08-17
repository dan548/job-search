package th.sibraine.jobagent.rendering.application

import th.sibraine.jobagent.rendering.domain.RenderedResumePdf
import th.sibraine.jobagent.rendering.domain.ResumePdfRenderer
import th.sibraine.jobagent.tailoring.application.TailorResumeUseCase
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class RenderResumeUseCase(
    private val variants: TailorResumeUseCase,
    private val renderer: ResumePdfRenderer,
) {
    fun execute(variantId: UUID): RenderedResumePdf = renderer.render(variants.get(variantId))
}
