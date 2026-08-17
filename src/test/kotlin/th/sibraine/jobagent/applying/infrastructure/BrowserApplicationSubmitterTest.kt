package th.sibraine.jobagent.applying.infrastructure

import th.sibraine.jobagent.applying.ApplicationWorkflowFixture
import th.sibraine.jobagent.applying.application.ApprovalDecision
import th.sibraine.jobagent.applying.application.RunBrowserApplicationCommand
import th.sibraine.jobagent.applying.application.RunBrowserApplicationUseCase
import th.sibraine.jobagent.applying.application.StartRunCommand
import th.sibraine.jobagent.applying.domain.*
import th.sibraine.jobagent.shared.ConflictException
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.ObjectProvider
import java.util.UUID

class BrowserApplicationSubmitterTest {
    @Test
    fun `presses submit in the browser only after the approval of the current state`() {
        val fixture = ApplicationWorkflowFixture()
        val runner = StubRunner()
        val draftId = readyDraft(fixture, runner)

        val approval = fixture.service.requestSubmitApproval(draftId)
        fixture.service.decideApproval(draftId, approval.approvalId, ApprovalDecision(approved = true))
        val view = fixture.service.submit(draftId)

        assertEquals(ApplicationStatus.SUBMITTED, view.draft.status)
        assertEquals(SubmissionMode.BROWSER, view.draft.submission!!.mode)
        assertEquals("https://jobs.example/apply/thanks", view.draft.submission!!.reference)
        val command = runner.submitCommand!!
        assertEquals(view.draft.stateFingerprint, command.approvedStateFingerprint)
        assertEquals(fixture.browserSessionService.session(draftId)!!.checkpoint, command.expectedCheckpoint)

        val session = fixture.browserSessionService.session(draftId)!!
        assertEquals(BrowserSessionStatus.SUBMITTED, session.status)
        assertEquals("https://jobs.example/apply/thanks", session.confirmationReference)
        assertEquals(
            "SUBMIT_CONFIRMED",
            fixture.browserSessionService.auditTrail(draftId).last().event,
        )
    }

    @Test
    fun `refuses to submit a page that changed after it was filled`() {
        val fixture = ApplicationWorkflowFixture()
        val runner = StubRunner()
        val draftId = readyDraft(fixture, runner)
        val approval = fixture.service.requestSubmitApproval(draftId)
        fixture.service.decideApproval(draftId, approval.approvalId, ApprovalDecision(approved = true))

        runner.checkpoint = "checkpoint-moved"
        val error = assertThrows<ConflictException> { fixture.service.submit(draftId) }

        assertEquals("BROWSER_CHECKPOINT_STALE", error.code)
        assertNull(runner.submitCommand)
        assertEquals(ApplicationStatus.READY_TO_SUBMIT, fixture.service.get(draftId).draft.status)
        val audit = fixture.browserSessionService.auditTrail(draftId).last()
        assertEquals("SUBMIT_REFUSED", audit.event)
        assertEquals("BROWSER_CHECKPOINT_STALE", audit.detailCode)
    }

    @Test
    fun `refuses to submit while the session is paused on a challenge`() {
        val fixture = ApplicationWorkflowFixture()
        val runner = StubRunner()
        val draftId = readyDraft(fixture, runner)
        val approval = fixture.service.requestSubmitApproval(draftId)
        fixture.service.decideApproval(draftId, approval.approvalId, ApprovalDecision(approved = true))
        fixture.browserSessionService.failed(draftId, "MANUAL_PAUSE")

        val error = assertThrows<ConflictException> { fixture.service.submit(draftId) }

        assertEquals("BROWSER_SESSION_PAUSED", error.code)
        assertNull(runner.submitCommand)
    }

    @Test
    fun `records a user submission when no browser session exists`() {
        val fixture = ApplicationWorkflowFixture()
        val runner = StubRunner()
        fixture.submitter = submitter(fixture, runner)
        val draftId = fixture.service.create(fixture.vacancyId).draft.draftId
        fixture.service.startRun(
            draftId,
            StartRunCommand(
                listOf(ObservedFormField("email", "Email", FormFieldType.EMAIL, true, locator = "#email")),
            ),
        )
        val approval = fixture.service.requestSubmitApproval(draftId)
        fixture.service.decideApproval(draftId, approval.approvalId, ApprovalDecision(approved = true))

        val view = fixture.service.submit(draftId, reference = "ACME-2026-17")

        assertEquals(SubmissionMode.MANUAL, view.draft.submission!!.mode)
        assertEquals("ACME-2026-17", view.draft.submission!!.reference)
        assertNull(runner.submitCommand)
    }

    /** Drives the real browser run so the draft, the session and the checkpoint all exist. */
    private fun readyDraft(fixture: ApplicationWorkflowFixture, runner: StubRunner): UUID {
        fixture.submitter = submitter(fixture, runner)
        val draftId = fixture.service.create(fixture.vacancyId).draft.draftId
        RunBrowserApplicationUseCase(fixture.service, runner, fixture.browserSessionService).execute(
            draftId,
            RunBrowserApplicationCommand("https://jobs.example/apply", "attempt-1"),
        )
        assertEquals(ApplicationStatus.READY_TO_SUBMIT, fixture.service.get(draftId).draft.status)
        return draftId
    }

    private fun submitter(fixture: ApplicationWorkflowFixture, runner: BrowserApplicationRunner):
        BrowserApplicationSubmitter {
        val runners = mockk<ObjectProvider<BrowserApplicationRunner>>()
        every { runners.getIfAvailable() } returns runner
        return BrowserApplicationSubmitter(
            runners,
            fixture.browserSessionService,
            ManualApplicationSubmitter(),
        )
    }

    private class StubRunner : BrowserApplicationRunner {
        var checkpoint = "checkpoint-1"
        var submitCommand: BrowserSubmitCommand? = null

        override fun inspect(command: BrowserInspectCommand) = BrowserFormSnapshot(
            url = command.formUrl,
            fields = listOf(ObservedFormField("email", "Email", FormFieldType.EMAIL, true, locator = "#email")),
            checkpoint = checkpoint,
        )

        override fun fill(command: BrowserFillCommand) = BrowserFillResult(
            checkpoint,
            command.actions.map { it.fieldKey },
        )

        override fun submit(command: BrowserSubmitCommand): SubmissionReceipt {
            submitCommand = command
            return SubmissionReceipt(
                mode = SubmissionMode.BROWSER,
                reference = "https://jobs.example/apply/thanks",
                note = "Submitted by the browser runner after explicit approval.",
            )
        }
    }
}
