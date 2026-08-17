package th.sibraine.jobagent.applying.infrastructure

import th.sibraine.jobagent.applying.application.BrowserSessionService
import th.sibraine.jobagent.applying.domain.*
import th.sibraine.jobagent.shared.ConflictException
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.annotation.Primary
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Presses Submit in the browser, but only for a state the user approved.
 *
 * Both halves of that state are re-checked immediately before the click: the `stateFingerprint` the
 * approval was granted for must still be the fingerprint of the draft, and the live page must still
 * match the checkpoint stored for the session. Anything else — a challenge, a paused session, a page
 * that changed after it was filled — refuses and leaves the draft in `READY_TO_SUBMIT`.
 *
 * Without a browser runner or a browser session it falls back to [ManualApplicationSubmitter], so
 * recording a submission the user made themselves stays the default behaviour.
 */
@Component
@Primary
class BrowserApplicationSubmitter(
    private val runners: ObjectProvider<BrowserApplicationRunner>,
    private val sessions: BrowserSessionService,
    private val manual: ManualApplicationSubmitter,
) : ApplicationSubmitter {
    override fun submit(command: SubmissionCommand): SubmissionReceipt {
        val draftId = command.draft.draftId
        val runner = runners.getIfAvailable() ?: return manual.submit(command)
        val session = sessions.session(draftId) ?: return manual.submit(command)

        if (session.status == BrowserSessionStatus.SUBMITTED) {
            return SubmissionReceipt(
                mode = SubmissionMode.BROWSER,
                reference = session.confirmationReference,
                note = "Already submitted by the browser runner for this session.",
            )
        }
        if (session.status != BrowserSessionStatus.ACTIVE) {
            refuse(
                session,
                "BROWSER_SESSION_PAUSED",
                "The browser session is ${session.status} and has to be resumed before submitting",
            )
        }
        val runId = session.lastRunId ?: refuse(
            session,
            "BROWSER_SESSION_INCOMPLETE",
            "The browser session has no completed run to submit",
        )
        if (command.approvedStateFingerprint != command.draft.stateFingerprint) {
            refuse(
                session,
                "APPLICATION_APPROVAL_STALE",
                "The approved application state no longer matches the draft",
            )
        }

        val key = submitKey(command)
        val snapshot = runner.inspect(
            BrowserInspectCommand(
                draftId = draftId,
                formUrl = session.formUrl,
                idempotencyKey = key,
                resume = sessions.resumeState(draftId),
            )
        )
        if (snapshot.challenges.isNotEmpty()) {
            refuse(
                session,
                "BROWSER_SUBMIT_BLOCKED",
                "The page asks for ${snapshot.challenges.sortedBy { it.name }.joinToString()} " +
                    "before it can be submitted",
            )
        }
        if (snapshot.checkpoint != session.checkpoint) {
            refuse(
                session,
                "BROWSER_CHECKPOINT_STALE",
                "The form changed after it was filled; resume the browser run before submitting",
            )
        }

        val receipt = try {
            runner.submit(
                BrowserSubmitCommand(
                    draftId = draftId,
                    runId = runId,
                    idempotencyKey = key,
                    expectedCheckpoint = session.checkpoint,
                    approvedStateFingerprint = command.approvedStateFingerprint,
                )
            )
        } catch (error: RuntimeException) {
            record(session, runId, "SUBMIT_FAILED", error::class.simpleName ?: "ERROR")
            sessions.failed(draftId, "SUBMIT_FAILED")
            throw error
        }

        record(session, runId, "SUBMIT_CONFIRMED", "APPROVAL_${command.approvalId}")
        sessions.submitted(draftId, receipt.reference)
        return receipt
    }

    private fun refuse(session: BrowserSession, code: String, message: String): Nothing {
        record(session, session.lastRunId, "SUBMIT_REFUSED", code)
        sessions.failed(session.draftId, code)
        throw ConflictException(code, message)
    }

    private fun record(session: BrowserSession, runId: UUID?, event: String, detailCode: String) {
        sessions.append(
            session.draftId,
            runId,
            session.checkpoint,
            listOf(BrowserAuditEntry(event, detailCode = detailCode)),
        )
    }

    /** One key per approved state: retrying the same approval never presses Submit a second time. */
    private fun submitKey(command: SubmissionCommand): String =
        "submit:${command.approvedStateFingerprint.take(32)}"
}
