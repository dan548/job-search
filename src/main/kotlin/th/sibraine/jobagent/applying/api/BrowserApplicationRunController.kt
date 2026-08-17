package th.sibraine.jobagent.applying.api

import th.sibraine.jobagent.applying.application.ApplicationDraftService
import th.sibraine.jobagent.applying.application.BrowserApplicationRunResult
import th.sibraine.jobagent.applying.application.BrowserSessionService
import th.sibraine.jobagent.applying.application.RunBrowserApplicationCommand
import th.sibraine.jobagent.applying.application.RunBrowserApplicationUseCase
import th.sibraine.jobagent.applying.domain.BrowserApplicationRunner
import th.sibraine.jobagent.applying.domain.BrowserAuditRecord
import th.sibraine.jobagent.applying.domain.BrowserSession
import th.sibraine.jobagent.applying.domain.BrowserDiagnosticSnapshot
import th.sibraine.jobagent.applying.domain.SemanticFormFiller
import th.sibraine.jobagent.shared.ConflictException
import th.sibraine.jobagent.shared.NotFoundException
import org.springframework.beans.factory.ObjectProvider
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*
import java.util.UUID

data class StartBrowserApplicationRunRequest(
    val formUrl: String,
    val idempotencyKey: String,
)

/**
 * Browser runs need a concrete adapter to supply [BrowserApplicationRunner]; without one every run
 * endpoint refuses with `BROWSER_RUNNER_DISABLED` and the manual submit stays the only way forward.
 *
 * The stored session and its audit trail are served regardless: they describe what a browser did
 * earlier and stay readable after the adapter is switched off again.
 */
@RestController
@RequestMapping("/api/v1/application-drafts")
class BrowserApplicationRunController(
    private val applications: ApplicationDraftService,
    private val runners: ObjectProvider<BrowserApplicationRunner>,
    private val filler: SemanticFormFiller,
    private val sessions: BrowserSessionService,
) {
    @PostMapping("/{draftId}/browser-runs")
    @ResponseStatus(HttpStatus.CREATED)
    fun start(
        @PathVariable draftId: UUID,
        @RequestBody request: StartBrowserApplicationRunRequest,
    ): BrowserApplicationRunResult = useCase().execute(
        draftId,
        RunBrowserApplicationCommand(request.formUrl, request.idempotencyKey),
    )

    /** Continues a paused session: re-scans the form after a challenge, an answer or a page change. */
    @PostMapping("/{draftId}/browser-runs/resume")
    @ResponseStatus(HttpStatus.CREATED)
    fun resume(@PathVariable draftId: UUID): BrowserApplicationRunResult = useCase().resume(draftId)

    @GetMapping("/{draftId}/browser-session")
    fun session(@PathVariable draftId: UUID): BrowserSession = sessions.session(draftId)
        ?: throw NotFoundException(
            "BROWSER_SESSION_NOT_FOUND",
            "No browser session exists for this application draft",
        )

    @GetMapping("/{draftId}/browser-audit")
    fun audit(@PathVariable draftId: UUID): List<BrowserAuditRecord> = sessions.auditTrail(draftId)

    /** PII-free snapshots for diagnosing restarts, validation pauses and dynamic forms. */
    @GetMapping("/{draftId}/browser-diagnostics")
    fun diagnostics(@PathVariable draftId: UUID): List<BrowserDiagnosticSnapshot> =
        sessions.diagnosticSnapshots(draftId)

    private fun useCase(): RunBrowserApplicationUseCase {
        val runner = runners.getIfAvailable() ?: throw ConflictException(
            "BROWSER_RUNNER_DISABLED",
            "No browser adapter is configured; submit the application manually instead",
        )
        return RunBrowserApplicationUseCase(applications, runner, sessions, filler)
    }
}
