package th.sibraine.jobagent.applying.api

import th.sibraine.jobagent.applying.application.*
import th.sibraine.jobagent.applying.domain.ApplicationDraftView
import th.sibraine.jobagent.applying.domain.ApplicationRun
import th.sibraine.jobagent.applying.domain.ApprovalRequest
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.time.Instant
import java.util.UUID

data class CreateApplicationDraftRequest(val resumeVariantId: UUID? = null)
data class ApplicationAnswersRequest(val answers: List<th.sibraine.jobagent.applying.application.AnswerSubmission>)
data class SubmitApplicationRequest(val reference: String? = null)
data class ManualSubmissionRequest(val submittedAt: Instant, val reference: String, val note: String? = null)
data class FailApplicationRequest(val reason: String)

@RestController
@RequestMapping("/api/v1")
class ApplicationDraftController(private val service: ApplicationDraftService) {
    @PostMapping("/vacancies/{vacancyId}/application-drafts")
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        @PathVariable vacancyId: UUID,
        @RequestBody(required = false) request: CreateApplicationDraftRequest?,
    ): ApplicationDraftView = service.create(vacancyId, request?.resumeVariantId)

    @GetMapping("/vacancies/{vacancyId}/application-drafts/latest")
    fun latest(@PathVariable vacancyId: UUID): ApplicationDraftView = service.latest(vacancyId)

    @GetMapping("/application-drafts/{draftId}")
    fun get(@PathVariable draftId: UUID): ApplicationDraftView = service.get(draftId)

    @PostMapping("/application-drafts/{draftId}/runs")
    @ResponseStatus(HttpStatus.CREATED)
    fun startRun(
        @PathVariable draftId: UUID,
        @RequestBody request: StartRunCommand,
    ): ApplicationRunResult = service.startRun(draftId, request)

    @GetMapping("/application-drafts/{draftId}/runs")
    fun runs(@PathVariable draftId: UUID): List<ApplicationRun> = service.runHistory(draftId)

    @PostMapping("/application-drafts/{draftId}/answers")
    fun answer(
        @PathVariable draftId: UUID,
        @RequestBody request: ApplicationAnswersRequest,
    ): ApplicationDraftView = service.answer(draftId, request.answers)

    @PostMapping("/application-drafts/{draftId}/approvals/{approvalId}/decision")
    fun decide(
        @PathVariable draftId: UUID,
        @PathVariable approvalId: UUID,
        @RequestBody request: ApprovalDecision,
    ): ApplicationDraftView = service.decideApproval(draftId, approvalId, request)

    @PostMapping("/application-drafts/{draftId}/submit-approval")
    @ResponseStatus(HttpStatus.CREATED)
    fun requestSubmitApproval(@PathVariable draftId: UUID): ApprovalRequest =
        service.requestSubmitApproval(draftId)

    @PostMapping("/application-drafts/{draftId}/submit")
    fun submit(
        @PathVariable draftId: UUID,
        @RequestBody(required = false) request: SubmitApplicationRequest?,
    ): ApplicationDraftView = service.submit(draftId, request?.reference)

    @PostMapping("/application-drafts/{draftId}/manual-submission")
    fun recordManualSubmission(
        @PathVariable draftId: UUID,
        @RequestBody request: ManualSubmissionRequest,
    ): ApplicationDraftView = service.recordManualSubmission(
        draftId = draftId,
        submittedAt = request.submittedAt,
        reference = request.reference,
        note = request.note,
    )

    @PostMapping("/application-drafts/{draftId}/fail")
    fun fail(
        @PathVariable draftId: UUID,
        @RequestBody request: FailApplicationRequest,
    ): ApplicationDraftView = service.fail(draftId, request.reason)

    @GetMapping("/application-drafts/{draftId}/artifacts/{artifactId}")
    fun artifact(
        @PathVariable draftId: UUID,
        @PathVariable artifactId: UUID,
    ): ResponseEntity<ByteArray> {
        val stored = service.artifact(draftId, artifactId)
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(stored.artifact.contentType))
            .contentLength(stored.content.size.toLong())
            .header(
                "Content-Disposition",
                ContentDisposition.attachment().filename(stored.artifact.fileName).build().toString(),
            )
            .header("X-Artifact-SHA256", stored.artifact.sha256)
            .body(stored.content)
    }
}
