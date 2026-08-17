package th.sibraine.jobagent.applying.application

import th.sibraine.jobagent.applying.ApplicationWorkflowFixture
import th.sibraine.jobagent.applying.domain.*
import th.sibraine.jobagent.shared.ConflictException
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.UUID

class RunBrowserApplicationUseCaseTest {
    @Test
    fun `observes the form fills known controls and uploads the tailored resume`() {
        val fixture = ApplicationWorkflowFixture()
        val draftId = fixture.service.create(fixture.vacancyId).draft.draftId
        val runner = RecordingRunner(
            fields = listOf(
                ObservedFormField("full_name", "Full name", required = true, locator = "#name"),
                ObservedFormField("email", "Email", FormFieldType.EMAIL, true, locator = "#email"),
                ObservedFormField("cv", "Resume", FormFieldType.FILE, true, locator = "#cv"),
            )
        )
        val useCase = useCase(fixture, runner)

        val result = useCase.execute(
            draftId,
            RunBrowserApplicationCommand("https://jobs.example/apply", "attempt-1"),
        )

        assertEquals(BrowserRunOutcome.FILLED, result.outcome)
        assertEquals(listOf("full_name", "email", "cv"), runner.fillCommand!!.actions.map { it.fieldKey })
        assertTrue(runner.fillCommand!!.actions.single { it.fieldKey == "cv" }.artifact!!.content.isNotEmpty())
        assertFalse(result.audit.joinToString().contains("ada@example.com"))

        val replay = useCase.execute(
            draftId,
            RunBrowserApplicationCommand("https://jobs.example/apply", "attempt-1"),
        )
        assertEquals(BrowserRunOutcome.REPLAYED, replay.outcome)
        assertEquals(1, runner.fillCalls)
    }

    @Test
    fun `pauses before filling when the page presents captcha`() {
        val fixture = ApplicationWorkflowFixture()
        val draftId = fixture.service.create(fixture.vacancyId).draft.draftId
        val runner = RecordingRunner(
            fields = emptyList(),
            challenges = setOf(BrowserChallenge.CAPTCHA),
        )

        val result = useCase(fixture, runner).execute(
            draftId,
            RunBrowserApplicationCommand("https://jobs.example/apply", "captcha-1"),
        )

        assertEquals(BrowserRunOutcome.PAUSED, result.outcome)
        assertEquals(setOf(BrowserChallenge.CAPTCHA), result.challenges)
        assertEquals(0, runner.fillCalls)
    }

    @Test
    fun `reports browser validation errors as a resumable pause`() {
        val fixture = ApplicationWorkflowFixture()
        val draftId = fixture.service.create(fixture.vacancyId).draft.draftId
        val runner = RecordingRunner(
            fields = listOf(ObservedFormField("email", "Email", locator = "#email")),
            validationErrors = listOf(BrowserValidationError(
                "email", "ada@example.com is invalid; see https://jobs.example/help?candidate=ada",
                "TYPE_MISMATCH",
            )),
        )

        val result = useCase(fixture, runner).execute(
            draftId,
            RunBrowserApplicationCommand("https://jobs.example/apply", "validation-1"),
        )

        assertEquals(BrowserRunOutcome.PAUSED, result.outcome)
        assertEquals("email", result.validationErrors.single().fieldKey)
        val session = fixture.browserSessionService.session(draftId)!!
        assertEquals(BrowserStopReason.VALIDATION_ERRORS, session.stopReason)
        assertEquals(BrowserFieldStatus.VALIDATION_ERROR, session.fieldStates.single().status)
        assertEquals("<email> is invalid; see <url>", session.validationErrors.single().message)
        assertEquals("TYPE_MISMATCH", session.validationErrors.single().code)
        assertTrue(fixture.browserAuditRows.any { it.event == "VALIDATION_ERROR" })
    }

    @Test
    fun `stores only pii-free diagnostic page summaries`() {
        val fixture = ApplicationWorkflowFixture()
        val draftId = fixture.service.create(fixture.vacancyId).draft.draftId
        val sensitiveKey = "ada@example.com"
        val runner = RecordingRunner(
            fields = listOf(ObservedFormField(sensitiveKey, "Ada Lovelace email", locator = "#ada")),
        )

        useCase(fixture, runner).execute(
            draftId,
            RunBrowserApplicationCommand(
                "https://jobs.example/apply/ada@example.com?phone=48111222333#personal",
                "diagnostic-1",
            ),
        )

        val snapshot = fixture.browserSessionService.diagnosticSnapshots(draftId).first()
        assertEquals("https://jobs.example", snapshot.origin)
        assertFalse(snapshot.toString().contains("ada@example.com"))
        assertFalse(snapshot.toString().contains("48111222333"))
        assertEquals(64, snapshot.pathHash.length)
        assertEquals(64, snapshot.fields.single().fieldKeyHash.length)
    }

    @Test
    fun `persists runner failure and a resumable stop reason`() {
        val fixture = ApplicationWorkflowFixture()
        val draftId = fixture.service.create(fixture.vacancyId).draft.draftId
        val runner = object : BrowserApplicationRunner {
            override fun inspect(command: BrowserInspectCommand): BrowserFormSnapshot =
                throw IllegalStateException("browser crashed with ada@example.com")
            override fun fill(command: BrowserFillCommand) = error("unused")
            override fun submit(command: BrowserSubmitCommand) = error("unused")
        }

        assertThrows<IllegalStateException> {
            RunBrowserApplicationUseCase(fixture.service, runner, fixture.browserSessionService).execute(
                draftId,
                RunBrowserApplicationCommand("https://jobs.example/apply", "failure-1"),
            )
        }

        val session = fixture.browserSessionService.session(draftId)!!
        assertEquals(BrowserSessionStatus.PAUSED, session.status)
        assertEquals(BrowserStopReason.RUNNER_ERROR, session.stopReason)
        assertEquals("ILLEGALSTATEEXCEPTION", session.failureCode)
        assertEquals("RUN_FAILED", fixture.browserAuditRows.single().event)
    }

    @Test
    fun `restores a persisted session with a new runner after backend restart`() {
        val fixture = ApplicationWorkflowFixture()
        val draftId = fixture.service.create(fixture.vacancyId).draft.draftId
        val beforeRestart = RecordingRunner(
            fields = emptyList(),
            challenges = setOf(BrowserChallenge.OTP),
            state = BrowserSessionState("https://jobs.example/apply/otp", "persisted-storage"),
        )
        useCase(fixture, beforeRestart).execute(
            draftId, RunBrowserApplicationCommand("https://jobs.example/apply", "restart-1"),
        )

        val afterRestart = RecordingRunner(
            fields = listOf(ObservedFormField("email", "Email", locator = "#email")),
        )
        useCase(fixture, afterRestart).resume(draftId)

        assertEquals("persisted-storage", afterRestart.restoredFrom?.storageState)
        assertEquals("https://jobs.example/apply/otp", afterRestart.restoredFrom?.currentUrl)
        assertEquals(1, fixture.browserSessionService.session(draftId)?.resumeCount)
    }

    @Test
    fun `persists session and audit so a paused run stays reconstructable`() {
        val fixture = ApplicationWorkflowFixture()
        val draftId = fixture.service.create(fixture.vacancyId).draft.draftId
        val runner = RecordingRunner(
            fields = emptyList(),
            challenges = setOf(BrowserChallenge.OTP),
            state = BrowserSessionState("https://jobs.example/apply/otp", "cookie-jar"),
        )

        useCase(fixture, runner).execute(
            draftId,
            RunBrowserApplicationCommand("https://jobs.example/apply", "otp-1"),
        )

        val session = fixture.browserSessionService.session(draftId)!!
        assertEquals(BrowserSessionStatus.PAUSED, session.status)
        assertEquals(setOf(BrowserChallenge.OTP), session.challenges)
        assertTrue(session.restorable)
        assertEquals("https://jobs.example/apply/otp", session.currentUrl)

        val audit = fixture.browserSessionService.auditTrail(draftId)
        assertEquals(listOf("RUN_PAUSED"), audit.map { it.event })
        assertEquals("OTP", audit.single().detailCode)
    }

    @Test
    fun `resumes the run after the user solved the challenge`() {
        val fixture = ApplicationWorkflowFixture()
        val draftId = fixture.service.create(fixture.vacancyId).draft.draftId
        val runner = RecordingRunner(
            fields = emptyList(),
            challenges = setOf(BrowserChallenge.CAPTCHA),
            state = BrowserSessionState("https://jobs.example/apply", "cookie-jar"),
        )
        val useCase = useCase(fixture, runner)
        useCase.execute(draftId, RunBrowserApplicationCommand("https://jobs.example/apply", "captcha-1"))

        // The user solved the CAPTCHA in the headed browser; the form is visible now.
        runner.challenges = emptySet()
        runner.fields = listOf(ObservedFormField("email", "Email", FormFieldType.EMAIL, true, locator = "#email"))
        val resumed = useCase.resume(draftId)

        assertEquals(BrowserRunOutcome.FILLED, resumed.outcome)
        assertEquals(listOf("email"), runner.fillCommand!!.actions.map { it.fieldKey })
        assertEquals("cookie-jar", runner.restoredFrom!!.storageState)
        val session = fixture.browserSessionService.session(draftId)!!
        assertEquals(BrowserSessionStatus.ACTIVE, session.status)
        assertEquals(1, session.resumeCount)
        assertTrue(session.challenges.isEmpty())
    }

    @Test
    fun `asks about a field that only appears after the form was filled`() {
        val fixture = ApplicationWorkflowFixture()
        val draftId = fixture.service.create(fixture.vacancyId).draft.draftId
        val runner = RecordingRunner(
            fields = listOf(ObservedFormField("email", "Email", FormFieldType.EMAIL, true, locator = "#email")),
        )
        // Filling the email reveals a required salary question on the same page.
        runner.afterFill = {
            runner.fields = runner.fields + ObservedFormField(
                "salary", "Desired salary", FormFieldType.NUMBER, required = true, locator = "#salary",
            )
        }

        val result = useCase(fixture, runner).execute(
            draftId,
            RunBrowserApplicationCommand("https://jobs.example/apply", "dynamic-1"),
        )

        assertEquals(BrowserRunOutcome.PAUSED, result.outcome)
        assertEquals(
            listOf("salary"),
            result.application.draft.pendingApprovals.map { it.fieldKey },
        )
        assertEquals(ApplicationStatus.NEEDS_INPUT, result.application.draft.draft.status)
        assertEquals(2, fixture.runRows.size)
    }

    @Test
    fun `refuses a second form URL before the browser is touched`() {
        val fixture = ApplicationWorkflowFixture()
        val draftId = fixture.service.create(fixture.vacancyId).draft.draftId
        val runner = RecordingRunner(
            fields = listOf(ObservedFormField("email", "Email", FormFieldType.EMAIL, true, locator = "#email")),
        )
        val useCase = useCase(fixture, runner)
        useCase.execute(draftId, RunBrowserApplicationCommand("https://jobs.example/apply", "attempt-1"))
        val inspections = runner.inspectCalls

        val error = assertThrows<ConflictException> {
            useCase.execute(draftId, RunBrowserApplicationCommand("https://other.example/apply", "attempt-2"))
        }

        assertEquals("BROWSER_SESSION_URL_MISMATCH", error.code)
        assertEquals(inspections, runner.inspectCalls)
    }

    @Test
    fun `keeps the session paused when a replayed attempt still faces the challenge`() {
        val fixture = ApplicationWorkflowFixture()
        val draftId = fixture.service.create(fixture.vacancyId).draft.draftId
        val runner = RecordingRunner(fields = emptyList(), challenges = setOf(BrowserChallenge.CAPTCHA))
        val useCase = useCase(fixture, runner)
        val command = RunBrowserApplicationCommand("https://jobs.example/apply", "captcha-1")
        useCase.execute(draftId, command)

        val replay = useCase.execute(draftId, command)

        assertEquals(BrowserRunOutcome.REPLAYED, replay.outcome)
        assertEquals(BrowserSessionStatus.PAUSED, replay.session!!.status)
        assertEquals(setOf(BrowserChallenge.CAPTCHA), replay.session!!.challenges)
    }

    @Test
    fun `refuses to resume a session that was already submitted`() {
        val fixture = ApplicationWorkflowFixture()
        val draftId = fixture.service.create(fixture.vacancyId).draft.draftId
        val runner = RecordingRunner(
            fields = listOf(ObservedFormField("email", "Email", FormFieldType.EMAIL, true, locator = "#email")),
        )
        val useCase = useCase(fixture, runner)
        useCase.execute(draftId, RunBrowserApplicationCommand("https://jobs.example/apply", "done-1"))
        fixture.browserSessionService.submitted(draftId, "https://jobs.example/thanks")

        val error = assertThrows<ConflictException> { useCase.resume(draftId) }
        assertEquals("BROWSER_SESSION_SUBMITTED", error.code)
    }

    private fun useCase(fixture: ApplicationWorkflowFixture, runner: RecordingRunner) =
        RunBrowserApplicationUseCase(fixture.service, runner, fixture.browserSessionService)

    private class RecordingRunner(
        var fields: List<ObservedFormField>,
        var challenges: Set<BrowserChallenge> = emptySet(),
        private val validationErrors: List<BrowserValidationError> = emptyList(),
        private val state: BrowserSessionState? = null,
    ) : BrowserApplicationRunner {
        var fillCommand: BrowserFillCommand? = null
        var fillCalls = 0
        var inspectCalls = 0
        var restoredFrom: BrowserSessionState? = null
        var afterFill: (() -> Unit)? = null
        private var revision = 0

        override fun inspect(command: BrowserInspectCommand): BrowserFormSnapshot {
            inspectCalls++
            command.resume?.let { restoredFrom = it }
            return BrowserFormSnapshot(command.formUrl, fields, "checkpoint-$revision", challenges)
        }

        override fun fill(command: BrowserFillCommand): BrowserFillResult {
            fillCalls++
            fillCommand = command
            afterFill?.invoke()
            revision++
            return BrowserFillResult(
                "checkpoint-$revision",
                command.actions.map { it.fieldKey },
                validationErrors = validationErrors,
            )
        }

        override fun submit(command: BrowserSubmitCommand) = SubmissionReceipt(
            SubmissionMode.BROWSER,
            reference = UUID.randomUUID().toString(),
        )

        override fun exportState(draftId: UUID): BrowserSessionState? = state
    }
}
