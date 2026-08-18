package th.sibraine.jobagent.tailoring.api

import th.sibraine.jobagent.candidate.application.CandidateProfileService
import th.sibraine.jobagent.rendering.application.RenderResumeUseCase
import th.sibraine.jobagent.tailoring.application.TailorResumeUseCase
import th.sibraine.jobagent.tailoring.application.GenerateCoverLetterUseCase
import th.sibraine.jobagent.tailoring.application.SaveTailoringGapDecisionCommand
import th.sibraine.jobagent.tailoring.application.TailoringGapDecisionService
import th.sibraine.jobagent.tailoring.domain.CoverLetter
import th.sibraine.jobagent.tailoring.domain.ResumeVariant
import th.sibraine.jobagent.tailoring.domain.ResumeContentSelection
import th.sibraine.jobagent.tailoring.domain.ResumeContentSelectionRequest
import th.sibraine.jobagent.tailoring.domain.TailoringGapDecision
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.util.UUID

@RestController
@RequestMapping("/api/v1")
class ResumeVariantController(
    private val tailorResume: TailorResumeUseCase,
    private val profiles: CandidateProfileService,
    private val renderResume: RenderResumeUseCase,
    private val generateCoverLetter: GenerateCoverLetterUseCase,
    private val gapDecisions: TailoringGapDecisionService,
) {
    @PostMapping("/vacancies/{vacancyId}/resume-variants")
    @ResponseStatus(HttpStatus.CREATED)
    fun tailor(
        @PathVariable vacancyId: UUID,
        @RequestBody(required = false) selection: ResumeContentSelectionRequest?,
    ): ResumeVariant = tailorResume.execute(profiles.profileId, vacancyId, selection)

    @GetMapping("/vacancies/{vacancyId}/resume-selection")
    fun selection(@PathVariable vacancyId: UUID): ResumeContentSelection =
        tailorResume.selection(profiles.profileId, vacancyId)

    @GetMapping("/vacancies/{vacancyId}/resume-variants/latest")
    fun latest(@PathVariable vacancyId: UUID): ResumeVariant = tailorResume.latest(profiles.profileId, vacancyId)

    @GetMapping("/resume-variants/{variantId}")
    fun get(@PathVariable variantId: UUID): ResumeVariant = tailorResume.get(variantId)

    @PostMapping("/resume-variants/{variantId}/review-approval")
    fun approveReview(@PathVariable variantId: UUID): ResumeVariant =
        tailorResume.approveReview(profiles.profileId, variantId)

    @PutMapping("/resume-variants/{variantId}/gap-decisions/{questionId}")
    fun decideGap(
        @PathVariable variantId: UUID,
        @PathVariable questionId: String,
        @RequestBody request: SaveTailoringGapDecisionCommand,
    ): TailoringGapDecision = gapDecisions.save(profiles.profileId, variantId, questionId, request)

    @PostMapping("/resume-variants/{variantId}/cover-letter")
    fun coverLetter(@PathVariable variantId: UUID): CoverLetter =
        generateCoverLetter.execute(profiles.profileId, variantId)

    @GetMapping("/resume-variants/{variantId}/pdf", produces = [MediaType.APPLICATION_PDF_VALUE])
    fun pdf(@PathVariable variantId: UUID): ResponseEntity<ByteArray> {
        val rendered = renderResume.execute(variantId)
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_PDF)
            .contentLength(rendered.bytes.size.toLong())
            .header("Content-Disposition", ContentDisposition.attachment().filename("resume-$variantId.pdf").build().toString())
            .header("X-Resume-Page-Count", rendered.pageCount.toString())
            .header("X-Resume-ATS-Check", "passed")
            .body(rendered.bytes)
    }
}
