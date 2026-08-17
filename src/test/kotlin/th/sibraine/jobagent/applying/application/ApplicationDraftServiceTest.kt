package th.sibraine.jobagent.applying.application

import th.sibraine.jobagent.applying.ApplicationWorkflowFixture
import th.sibraine.jobagent.applying.domain.*
import th.sibraine.jobagent.shared.ConflictException
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.UUID

class ApplicationDraftServiceTest {
    private val fixture = ApplicationWorkflowFixture()
    private val service = fixture.service

    @Test
    fun `replays the same run key and rejects reuse for another observed form`() {
        val draftId = createDraft()
        val command = StartRunCommand(form(), "https://jobs.example/apply", "attempt-1")

        val first = service.startRun(draftId, command)
        val replay = service.startRun(draftId, command)

        assertFalse(first.replayed)
        assertTrue(replay.replayed)
        assertEquals(first.run.runId, replay.run.runId)
        assertEquals(1, fixture.runRows.size)

        val error = assertThrows<ConflictException> {
            service.startRun(
                draftId,
                command.copy(observedFields = form() + ObservedFormField("extra", "Extra")),
            )
        }
        assertEquals("APPLICATION_RUN_IDEMPOTENCY_CONFLICT", error.code)
    }

    @Test
    fun `fills known fields, pauses on a sensitive question and submits only after a separate approval`() {
        val draftId = createDraft()

        val run = service.startRun(draftId, StartRunCommand(observedFields = form())).run
        assertEquals(ApplicationRunStatus.NEEDS_INPUT, run.status)
        assertEquals(listOf("full_name", "email", "cv"), run.filledFieldKeys)
        assertEquals(listOf("salary"), run.pendingFieldKeys)

        val paused = service.get(draftId)
        assertEquals(ApplicationStatus.NEEDS_INPUT, paused.draft.status)
        val question = paused.pendingApprovals.single()
        assertEquals(FormFieldTopic.DESIRED_SALARY, question.topic)
        assertEquals(QuestionReason.SENSITIVE_TOPIC, question.reason)
        assertEquals(ApplicationArtifactType.RESUME_PDF, paused.artifacts.single().type)

        val answered = service.answer(
            draftId,
            listOf(AnswerSubmission("salary", "28000 PLN per month", saveToCatalog = true)),
        )
        assertEquals(ApplicationStatus.READY_TO_SUBMIT, answered.draft.status)
        assertTrue(answered.pendingApprovals.isEmpty())
        assertEquals("28000 PLN per month", answered.draft.answers.single { it.fieldKey == "salary" }.value)
        assertEquals(listOf(FormFieldTopic.DESIRED_SALARY.name), fixture.catalog.map { it.key })

        val tooEarly = assertThrows<ConflictException> { service.submit(draftId) }
        assertEquals("APPLICATION_SUBMIT_NOT_APPROVED", tooEarly.code)

        val approval = service.requestSubmitApproval(draftId)
        assertEquals(answered.draft.stateFingerprint, approval.stateFingerprint)
        service.decideApproval(draftId, approval.approvalId, ApprovalDecision(approved = true))

        val submitted = service.submit(draftId, reference = "ACME-2026-17")
        assertEquals(ApplicationStatus.SUBMITTED, submitted.draft.status)
        assertEquals(SubmissionMode.MANUAL, submitted.draft.submission!!.mode)
        assertEquals("ACME-2026-17", submitted.draft.submission!!.reference)
        assertEquals(approval.approvalId, submitted.draft.submission!!.approvalId)
        assertEquals(submitted.draft, service.submit(draftId).draft)
    }

    @Test
    fun `an approval stops covering the application as soon as an answer changes`() {
        val draftId = createDraft()
        service.startRun(draftId, StartRunCommand(observedFields = form()))
        service.answer(draftId, listOf(AnswerSubmission("salary", "28000 PLN per month")))
        val approval = service.requestSubmitApproval(draftId)
        service.decideApproval(draftId, approval.approvalId, ApprovalDecision(approved = true))

        service.answer(draftId, listOf(AnswerSubmission("email", "ada@другой.example")))

        val error = assertThrows<ConflictException> { service.submit(draftId) }
        assertEquals("APPLICATION_SUBMIT_NOT_APPROVED", error.code)
        assertEquals(ApplicationStatus.READY_TO_SUBMIT, service.get(draftId).draft.status)
    }

    @Test
    fun `a pending submit approval is retired when the application changes under it`() {
        val draftId = createDraft()
        service.startRun(draftId, StartRunCommand(observedFields = form()))
        service.answer(draftId, listOf(AnswerSubmission("salary", "28000 PLN per month")))
        val approval = service.requestSubmitApproval(draftId)

        service.answer(draftId, listOf(AnswerSubmission("email", "ada@другой.example")))

        val error = assertThrows<ConflictException> {
            service.decideApproval(draftId, approval.approvalId, ApprovalDecision(approved = true))
        }
        assertEquals("APPLICATION_APPROVAL_STALE", error.code)
        assertTrue(service.get(draftId).pendingApprovals.none { it.type == ApprovalType.SUBMIT })
    }

    @Test
    fun `a declined optional question is recorded instead of being answered`() {
        val draftId = createDraft()
        val fields = form() + ObservedFormField(
            fieldKey = "gender",
            label = "Gender",
            type = FormFieldType.SELECT,
            required = false,
            options = listOf("Female", "Male", "Prefer not to say"),
        )
        service.startRun(draftId, StartRunCommand(observedFields = fields))

        val view = service.answer(
            draftId,
            listOf(
                AnswerSubmission("salary", "28000 PLN per month"),
                AnswerSubmission("gender", declined = true),
            ),
        )

        val gender = view.draft.answers.single { it.fieldKey == "gender" }
        assertNull(gender.value)
        assertEquals(AnswerSource.DECLINED_BY_USER, gender.source)
        assertEquals(ApplicationStatus.READY_TO_SUBMIT, view.draft.status)
    }

    @Test
    fun `refuses a second open draft for the same vacancy and any work after a terminal state`() {
        val draftId = createDraft()

        val duplicate = assertThrows<ConflictException> { service.create(fixture.vacancyId) }
        assertEquals("APPLICATION_DRAFT_ALREADY_OPEN", duplicate.code)

        service.fail(draftId, "The form requires a login the agent does not have")
        val failed = service.get(draftId)
        assertEquals(ApplicationStatus.FAILED, failed.draft.status)
        assertTrue(failed.pendingApprovals.isEmpty())
        assertThrows<InvalidApplicationTransitionException> {
            service.startRun(draftId, StartRunCommand(observedFields = form()))
        }
        assertDoesNotThrow { service.create(fixture.vacancyId) }
    }

    private fun createDraft(): UUID = service.create(fixture.vacancyId).draft.draftId

    private fun form() = listOf(
        ObservedFormField("full_name", "Full name", required = true),
        ObservedFormField("email", "Email", FormFieldType.EMAIL, required = true),
        ObservedFormField("cv", "Upload your CV", FormFieldType.FILE, required = true),
        ObservedFormField("salary", "Desired salary", required = true),
    )
}
