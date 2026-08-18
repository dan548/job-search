package th.sibraine.jobagent.applying.application

import th.sibraine.jobagent.applying.domain.*
import th.sibraine.jobagent.shared.ConflictException
import th.sibraine.jobagent.shared.NotFoundException
import java.util.UUID

data class RunBrowserApplicationCommand(
    val formUrl: String,
    val idempotencyKey: String,
)

enum class BrowserRunOutcome { FILLED, PAUSED, REPLAYED, RESCANNED }

data class BrowserApplicationRunResult(
    val outcome: BrowserRunOutcome,
    val application: ApplicationRunResult,
    val checkpoint: String,
    val session: BrowserSession? = null,
    val challenges: Set<BrowserChallenge> = emptySet(),
    val validationErrors: List<BrowserValidationError> = emptyList(),
    val audit: List<BrowserAuditEntry> = emptyList(),
)

/**
 * Coordinates browser observation and filling with the existing evidence and approval workflow.
 *
 * A run is resumable. Every pass persists its checkpoint and audit trail, so [resume] can continue
 * after a CAPTCHA, an OTP, a manual sign-in or a backend restart: the form is observed again, fields
 * that appeared meanwhile become new questions, and only fields that were not applied yet are filled.
 * The same re-scan carries a multi-page questionnaire from one step to the next.
 */
class RunBrowserApplicationUseCase(
    private val applications: ApplicationDraftService,
    private val runner: BrowserApplicationRunner,
    private val sessions: BrowserSessionService,
    private val filler: SemanticFormFiller = SemanticFormFiller(),
    private val maxPasses: Int = DEFAULT_MAX_PASSES,
) {
    fun execute(draftId: UUID, command: RunBrowserApplicationCommand): BrowserApplicationRunResult {
        require(command.formUrl.startsWith("https://")) { "Browser application URL must use HTTPS" }
        require(command.idempotencyKey.isNotBlank()) { "Browser run idempotencyKey must not be blank" }
        require(command.idempotencyKey.length <= MAX_BASE_KEY_LENGTH) {
            "Browser run idempotencyKey must contain at most $MAX_BASE_KEY_LENGTH characters"
        }
        sessions.session(draftId)?.let { session ->
            requireResumable(session)
            // Checked before the browser is touched: a second form URL would otherwise be observed
            // and filled before the session could reject it.
            if (session.formUrl != command.formUrl) {
                throw ConflictException(
                    "BROWSER_SESSION_URL_MISMATCH",
                    "A browser session for this draft is already bound to another form URL",
                )
            }
        }
        return advance(draftId, command.formUrl, command.idempotencyKey, resumed = false)
    }

    /**
     * Re-scans the live form of an existing session. Used after the user solved a challenge, answered
     * a pending question or moved the page forward by hand.
     */
    fun resume(draftId: UUID): BrowserApplicationRunResult {
        val session = sessions.session(draftId) ?: throw NotFoundException(
            "BROWSER_SESSION_NOT_FOUND",
            "No browser session exists for this application draft",
        )
        requireResumable(session)
        return advance(draftId, session.formUrl, session.baseIdempotencyKey, resumed = true)
    }

    private fun advance(
        draftId: UUID,
        formUrl: String,
        baseKey: String,
        resumed: Boolean,
    ): BrowserApplicationRunResult {
        val existing = sessions.session(draftId)
        val progress = RunProgress(existing?.fieldStates.orEmpty())
        var restore = if (resumed) sessions.resumeState(draftId) else null
        var pass = 0

        while (true) {
            val snapshot = try {
                runner.inspect(BrowserInspectCommand(draftId, formUrl, baseKey, restore))
            } catch (error: RuntimeException) {
                persistFailure(draftId, formUrl, baseKey, resumed, progress, error)
                throw error
            }
            restore = null
            val digest = FormObservationDigest.of(snapshot.url, snapshot.challenges, snapshot.fields)
            val previousDigest = progress.digest
            progress.observe(snapshot.url, snapshot.checkpoint, digest)
            progress.observedFields = snapshot.fields
            progress.observeFields(snapshot.fields)
            // What the page shows now, so even a replayed attempt persists a truthful session.
            progress.challenges = snapshot.challenges

            if (digest == previousDigest) {
                // The page is unchanged since the last fill: nothing new appeared to answer.
                progress.audit += BrowserAuditEntry("FORM_STABLE", detailCode = "NO_NEW_FIELDS")
                return finish(draftId, formUrl, baseKey, resumed, progress, BrowserRunOutcome.FILLED)
            }
            if (snapshot.fields.isEmpty() && snapshot.challenges.isEmpty()) {
                if (pass == 0) {
                    persistFailure(
                        draftId, formUrl, baseKey, resumed, progress,
                        IllegalStateException("BROWSER_FORM_EMPTY"),
                        "BROWSER_FORM_EMPTY",
                    )
                    throw ConflictException(
                        "BROWSER_FORM_EMPTY",
                        "The current page exposes no form fields and no challenge to solve",
                    )
                }
                progress.audit += BrowserAuditEntry("FORM_STABLE", detailCode = "NO_FIELDS")
                return finish(draftId, formUrl, baseKey, resumed, progress, BrowserRunOutcome.FILLED)
            }

            // The first attempt owns the caller's key; every later observation derives its own, so a
            // repeated re-scan of one page state replays instead of starting a second run for it.
            val key = if (pass == 0 && !resumed) baseKey else derivedKey(baseKey, digest)
            val application = applications.startRun(
                draftId,
                StartRunCommand(snapshot.fields, snapshot.url, key, snapshot.challenges),
            )
            progress.application = application
            application.run.pendingFieldKeys.forEach {
                progress.fieldStatus(it, BrowserFieldStatus.PENDING_INPUT, "PENDING_ANSWER")
            }
            if (application.replayed) {
                if (snapshot.challenges.isNotEmpty()) progress.stopReason = BrowserStopReason.CHALLENGE
                progress.audit += BrowserAuditEntry("RUN_REPLAYED", detailCode = "IDEMPOTENCY_KEY")
                val outcome = if (pass == 0) BrowserRunOutcome.REPLAYED else BrowserRunOutcome.FILLED
                return finish(draftId, formUrl, baseKey, resumed, progress, outcome)
            }

            if (snapshot.challenges.isNotEmpty()) {
                progress.stopReason = BrowserStopReason.CHALLENGE
                progress.audit += BrowserAuditEntry("RUN_PAUSED", detailCode = codes(snapshot.challenges))
                return finish(draftId, formUrl, baseKey, resumed, progress, BrowserRunOutcome.PAUSED)
            }

            val plan = filler.plan(
                snapshot = snapshot,
                answers = application.draft.draft.answers,
                artifacts = application.draft.artifacts,
                artifact = { metadata ->
                    val stored = applications.artifact(draftId, metadata.artifactId)
                    BrowserArtifactPayload(
                        metadata.artifactId,
                        metadata.fileName,
                        metadata.contentType,
                        stored.content,
                    )
                },
            )
            progress.audit += plan.audit
            plan.audit.forEach { entry ->
                when (entry.event) {
                    "FIELD_PLANNED" -> progress.fieldStatus(
                        requireNotNull(entry.fieldKey), BrowserFieldStatus.PLANNED, entry.detailCode,
                    )
                    "FIELD_SKIPPED" -> progress.fieldStatus(
                        requireNotNull(entry.fieldKey), BrowserFieldStatus.SKIPPED, entry.detailCode,
                    )
                }
            }
            val actions = plan.actions.filterNot { it.fieldKey in progress.applied }
            if (actions.isEmpty()) {
                if (application.draft.draft.status == ApplicationStatus.NEEDS_INPUT) {
                    progress.stopReason = BrowserStopReason.PENDING_ANSWERS
                    progress.audit += BrowserAuditEntry("RUN_PAUSED", detailCode = "PENDING_ANSWERS")
                }
                return finish(draftId, formUrl, baseKey, resumed, progress, outcomeFor(application))
            }

            val result = try {
                runner.fill(BrowserFillCommand(
                    draftId = draftId,
                    runId = application.run.runId,
                    idempotencyKey = key,
                    expectedCheckpoint = snapshot.checkpoint,
                    actions = actions,
                ))
            } catch (error: RuntimeException) {
                persistFailure(draftId, formUrl, baseKey, resumed, progress, error)
                throw error
            }
            progress.checkpoint = result.checkpoint
            progress.applied += result.appliedFieldKeys
            result.appliedFieldKeys.forEach {
                progress.fieldStatus(it, BrowserFieldStatus.APPLIED, "VALUE_REDACTED")
            }
            progress.challenges = result.challenges
            progress.validationErrors = result.validationErrors
            result.validationErrors.forEach { error ->
                error.fieldKey?.let {
                    progress.fieldStatus(it, BrowserFieldStatus.VALIDATION_ERROR, error.code)
                }
                progress.audit += BrowserAuditEntry(
                    "VALIDATION_ERROR", error.fieldKey, error.code,
                )
            }
            progress.audit += result.appliedFieldKeys.map {
                BrowserAuditEntry("FIELD_APPLIED", it, "VALUE_REDACTED")
            }

            if (result.challenges.isNotEmpty()) {
                progress.stopReason = BrowserStopReason.CHALLENGE
                progress.audit += BrowserAuditEntry("RUN_PAUSED", detailCode = pauseReason(result))
                return finish(draftId, formUrl, baseKey, resumed, progress, BrowserRunOutcome.PAUSED)
            }
            if (application.draft.draft.status == ApplicationStatus.NEEDS_INPUT) {
                progress.stopReason = BrowserStopReason.PENDING_ANSWERS
                progress.audit += BrowserAuditEntry("RUN_PAUSED", detailCode = "PENDING_ANSWERS")
                return finish(draftId, formUrl, baseKey, resumed, progress, BrowserRunOutcome.PAUSED)
            }
            if (result.validationErrors.isNotEmpty()) {
                progress.stopReason = BrowserStopReason.VALIDATION_ERRORS
                progress.audit += BrowserAuditEntry("RUN_PAUSED", detailCode = "VALIDATION_ERRORS")
                return finish(draftId, formUrl, baseKey, resumed, progress, BrowserRunOutcome.PAUSED)
            }

            pass++
            if (pass >= maxPasses) {
                progress.stopReason = BrowserStopReason.RESCAN_LIMIT
                progress.audit += BrowserAuditEntry("RESCAN_LIMIT", detailCode = "MAX_PASSES")
                return finish(draftId, formUrl, baseKey, resumed, progress, BrowserRunOutcome.RESCANNED)
            }
        }
    }

    /** Persists the checkpoint and the audit trail of this attempt before answering the caller. */
    private fun finish(
        draftId: UUID,
        formUrl: String,
        baseKey: String,
        resumed: Boolean,
        progress: RunProgress,
        outcome: BrowserRunOutcome,
    ): BrowserApplicationRunResult {
        val application = checkNotNull(progress.application) { "A browser run always records a run" }
        val session = sessions.record(
            BrowserSessionUpdate(
                draftId = draftId,
                formUrl = formUrl,
                currentUrl = progress.currentUrl,
                checkpoint = progress.checkpoint,
                observationDigest = progress.digest.orEmpty(),
                baseIdempotencyKey = baseKey,
                status = if (outcome == BrowserRunOutcome.PAUSED || progress.challenges.isNotEmpty()) {
                    BrowserSessionStatus.PAUSED
                } else {
                    BrowserSessionStatus.ACTIVE
                },
                challenges = progress.challenges,
                observedFields = progress.observedFields,
                fieldStates = progress.fieldStates.values.toList(),
                validationErrors = progress.validationErrors,
                stopReason = progress.stopReason,
                runId = application.run.runId,
                state = runner.exportState(draftId),
                resumed = resumed,
            )
        )
        sessions.append(draftId, application.run.runId, progress.checkpoint, progress.audit)
        return BrowserApplicationRunResult(
            outcome = outcome,
            application = application,
            checkpoint = progress.checkpoint,
            session = session,
            challenges = progress.challenges,
            validationErrors = progress.validationErrors,
            audit = progress.audit.toList(),
        )
    }

    private fun requireResumable(session: BrowserSession) {
        if (session.status == BrowserSessionStatus.SUBMITTED) {
            throw ConflictException(
                "BROWSER_SESSION_SUBMITTED",
                "This application was already submitted through the browser",
            )
        }
    }

    private fun outcomeFor(application: ApplicationRunResult): BrowserRunOutcome =
        if (application.draft.draft.status == ApplicationStatus.NEEDS_INPUT) {
            BrowserRunOutcome.PAUSED
        } else {
            BrowserRunOutcome.FILLED
        }

    private fun pauseReason(result: BrowserFillResult): String = when {
        result.challenges.isNotEmpty() -> codes(result.challenges)
        else -> "VALIDATION_ERRORS"
    }

    private fun codes(challenges: Set<BrowserChallenge>): String =
        challenges.sortedBy { it.name }.joinToString(",") { it.name }

    private fun derivedKey(baseKey: String, digest: String): String =
        "$baseKey:${digest.take(DERIVED_KEY_DIGEST_LENGTH)}"

    private fun persistFailure(
        draftId: UUID,
        formUrl: String,
        baseKey: String,
        resumed: Boolean,
        progress: RunProgress,
        error: RuntimeException,
        explicitCode: String? = null,
    ) {
        val code = (explicitCode ?: error::class.simpleName ?: "BROWSER_RUNNER_ERROR")
            .uppercase().replace(Regex("[^A-Z0-9_]+"), "_").take(120)
        val runId = progress.application?.run?.runId
        sessions.record(
            BrowserSessionUpdate(
                draftId = draftId,
                formUrl = formUrl,
                currentUrl = progress.currentUrl.ifBlank { formUrl },
                checkpoint = progress.checkpoint.ifBlank { "unavailable" },
                observationDigest = progress.digest.orEmpty(),
                baseIdempotencyKey = baseKey,
                status = BrowserSessionStatus.PAUSED,
                challenges = progress.challenges,
                observedFields = progress.observedFields,
                fieldStates = progress.fieldStates.values.toList(),
                validationErrors = progress.validationErrors,
                stopReason = BrowserStopReason.RUNNER_ERROR,
                failureCode = code,
                runId = runId,
                state = runCatching { runner.exportState(draftId) }.getOrNull(),
                resumed = resumed,
            )
        )
        sessions.append(
            draftId, runId, progress.checkpoint.takeIf { it.isNotBlank() },
            listOf(BrowserAuditEntry("RUN_FAILED", detailCode = code)),
        )
    }

    private class RunProgress(initialFieldStates: List<BrowserFieldState>) {
        val audit = mutableListOf<BrowserAuditEntry>()
        val fieldStates = initialFieldStates.associateByTo(linkedMapOf()) { it.fieldKey }
        val applied = initialFieldStates.filter { it.status == BrowserFieldStatus.APPLIED }
            .mapTo(mutableSetOf()) { it.fieldKey }
        var application: ApplicationRunResult? = null
        var currentUrl: String = ""
        var checkpoint: String = ""
        var digest: String? = null
        var challenges: Set<BrowserChallenge> = emptySet()
        var validationErrors: List<BrowserValidationError> = emptyList()
        var observedFields: List<ObservedFormField> = emptyList()
        var stopReason: BrowserStopReason? = null

        fun observe(url: String, checkpoint: String, digest: String) {
            currentUrl = url
            this.checkpoint = checkpoint
            this.digest = digest
        }

        fun observeFields(fields: List<ObservedFormField>) {
            fields.forEach { field ->
                if (field.fieldKey !in fieldStates) {
                    fieldStatus(field.fieldKey, BrowserFieldStatus.OBSERVED)
                }
            }
        }

        fun fieldStatus(fieldKey: String, status: BrowserFieldStatus, detailCode: String? = null) {
            val previous = fieldStates[fieldKey]?.status
            if (previous == BrowserFieldStatus.APPLIED &&
                status in setOf(BrowserFieldStatus.OBSERVED, BrowserFieldStatus.PLANNED)
            ) return
            fieldStates[fieldKey] = BrowserFieldState(fieldKey, status, detailCode)
        }
    }

    private companion object {
        /** One pass fills, the next picks up fields that appeared because of it. */
        private const val DEFAULT_MAX_PASSES = 3
        private const val DERIVED_KEY_DIGEST_LENGTH = 32
        private const val MAX_BASE_KEY_LENGTH = 95
    }
}
