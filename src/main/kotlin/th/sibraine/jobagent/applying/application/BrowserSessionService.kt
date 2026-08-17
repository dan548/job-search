package th.sibraine.jobagent.applying.application

import th.sibraine.jobagent.applying.domain.*
import th.sibraine.jobagent.applying.infrastructure.BrowserAuditEntryEntity
import th.sibraine.jobagent.applying.infrastructure.BrowserAuditEntryJpaRepository
import th.sibraine.jobagent.applying.infrastructure.BrowserSessionEntity
import th.sibraine.jobagent.applying.infrastructure.BrowserSessionJpaRepository
import th.sibraine.jobagent.applying.infrastructure.BrowserDiagnosticSnapshotEntity
import th.sibraine.jobagent.applying.infrastructure.BrowserDiagnosticSnapshotJpaRepository
import th.sibraine.jobagent.shared.ConflictException
import th.sibraine.jobagent.shared.NotFoundException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.net.URI
import java.security.MessageDigest
import java.util.UUID

data class BrowserSessionUpdate(
    val draftId: UUID,
    val formUrl: String,
    val currentUrl: String,
    val checkpoint: String,
    val observationDigest: String,
    val baseIdempotencyKey: String,
    val status: BrowserSessionStatus,
    val challenges: Set<BrowserChallenge> = emptySet(),
    val observedFields: List<ObservedFormField> = emptyList(),
    val fieldStates: List<BrowserFieldState> = emptyList(),
    val validationErrors: List<BrowserValidationError> = emptyList(),
    val stopReason: BrowserStopReason? = null,
    val failureCode: String? = null,
    val runId: UUID? = null,
    val state: BrowserSessionState? = null,
    val resumed: Boolean = false,
)

/**
 * Keeps the browser session and its audit trail in PostgreSQL, so a run survives a backend restart
 * and every browser action stays reconstructable afterwards.
 *
 * [append] and [failed] commit on their own transaction on purpose: the trail of a submit that was
 * rejected or failed must outlive the rollback of the workflow transaction that triggered it.
 */
@Service
class BrowserSessionService(
    private val sessions: BrowserSessionJpaRepository,
    private val entries: BrowserAuditEntryJpaRepository,
    private val diagnostics: BrowserDiagnosticSnapshotJpaRepository,
    private val clock: Clock,
) {
    @Transactional(readOnly = true)
    fun session(draftId: UUID): BrowserSession? = sessions.findByDraftId(draftId)?.toDomain()

    /** The stored state a runner may rebuild the session from; never exposed through the API. */
    @Transactional(readOnly = true)
    fun resumeState(draftId: UUID): BrowserSessionState? = sessions.findByDraftId(draftId)
        ?.let { BrowserSessionState(it.currentUrl, it.storageState) }

    @Transactional(readOnly = true)
    fun auditTrail(draftId: UUID): List<BrowserAuditRecord> = entries
        .findByDraftIdOrderBySequenceAsc(draftId)
        .map { it.toDomain() }

    @Transactional(readOnly = true)
    fun diagnosticSnapshots(draftId: UUID): List<BrowserDiagnosticSnapshot> = diagnostics
        .findByDraftIdOrderBySequenceAsc(draftId)
        .map { it.toDomain() }

    @Transactional
    fun record(update: BrowserSessionUpdate): BrowserSession {
        val now = Instant.now(clock)
        val existing = sessions.findByDraftId(update.draftId)
        if (existing != null && existing.formUrl != update.formUrl) {
            throw ConflictException(
                "BROWSER_SESSION_URL_MISMATCH",
                "A browser session for this draft is already bound to another form URL",
            )
        }
        val entity = existing ?: BrowserSessionEntity(
            sessionId = UUID.randomUUID(),
            draftId = update.draftId,
            status = update.status,
            formUrl = update.formUrl,
            currentUrl = update.currentUrl,
            checkpoint = update.checkpoint,
            observationDigest = update.observationDigest,
            baseIdempotencyKey = update.baseIdempotencyKey,
            createdAt = now,
            updatedAt = now,
        )
        entity.status = update.status
        entity.currentUrl = update.state?.currentUrl ?: update.currentUrl
        entity.checkpoint = update.checkpoint
        entity.observationDigest = update.observationDigest
        entity.challenges = update.challenges.sortedBy { it.name }
        entity.fieldStates = update.fieldStates.sortedBy { it.fieldKey }
        entity.validationErrors = update.validationErrors.map(::sanitizeValidationError)
        entity.stopReason = update.stopReason
        update.runId?.let { entity.lastRunId = it }
        if (update.resumed && existing != null) entity.resumeCount += 1
        // An absent storage state keeps the previous one: a runner that cannot export must not erase
        // the state a restart would need.
        update.state?.storageState?.let { entity.storageState = it }
        entity.failureCode = update.failureCode?.take(120)
        entity.updatedAt = now
        val saved = sessions.save(entity).toDomain()
        diagnostics.save(diagnosticSnapshot(update, now))
        return saved
    }

    @Transactional
    fun submitted(draftId: UUID, reference: String?): BrowserSession {
        val entity = requireSession(draftId)
        entity.status = BrowserSessionStatus.SUBMITTED
        entity.confirmationReference = reference
        entity.failureCode = null
        entity.stopReason = null
        entity.validationErrors = emptyList()
        entity.updatedAt = Instant.now(clock)
        return sessions.save(entity).toDomain()
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun failed(draftId: UUID, code: String) {
        val entity = sessions.findByDraftId(draftId) ?: return
        if (entity.status == BrowserSessionStatus.SUBMITTED) return
        entity.status = BrowserSessionStatus.PAUSED
        entity.failureCode = code.take(120)
        entity.stopReason = BrowserStopReason.SUBMIT_ERROR
        entity.updatedAt = Instant.now(clock)
        sessions.save(entity)
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun append(
        draftId: UUID,
        runId: UUID?,
        checkpoint: String?,
        audit: List<BrowserAuditEntry>,
    ) {
        if (audit.isEmpty()) return
        val now = Instant.now(clock)
        audit.forEach { entry ->
            entries.save(
                BrowserAuditEntryEntity(
                    draftId = draftId,
                    runId = runId,
                    event = entry.event.take(40),
                    fieldKey = entry.fieldKey?.take(255),
                    detailCode = entry.detailCode?.take(120),
                    checkpoint = checkpoint,
                    recordedAt = now,
                )
            )
        }
    }

    private fun requireSession(draftId: UUID): BrowserSessionEntity = sessions.findByDraftId(draftId)
        ?: throw NotFoundException(
            "BROWSER_SESSION_NOT_FOUND",
            "No browser session exists for this application draft",
        )

    private fun diagnosticSnapshot(update: BrowserSessionUpdate, now: Instant): BrowserDiagnosticSnapshotEntity {
        val uri = runCatching { URI(update.currentUrl) }.getOrNull()
        val origin = if (uri?.scheme != null && uri.host != null) {
            buildString {
                append(uri.scheme.lowercase()).append("://").append(uri.host.lowercase())
                if (uri.port >= 0) append(':').append(uri.port)
            }
        } else {
            "invalid://redacted"
        }
        val pathHash = sha256((uri?.rawPath ?: "").toByteArray())
        val states = update.fieldStates.associateBy { it.fieldKey }
        return BrowserDiagnosticSnapshotEntity(
            draftId = update.draftId,
            runId = update.runId,
            origin = origin,
            pathHash = pathHash,
            checkpoint = update.checkpoint.take(64),
            observationDigest = update.observationDigest.take(64),
            fields = update.observedFields.map { field ->
                BrowserDiagnosticField(
                    fieldKeyHash = sha256(field.fieldKey.toByteArray()),
                    type = field.type,
                    required = field.required,
                    status = states[field.fieldKey]?.status ?: BrowserFieldStatus.OBSERVED,
                )
            },
            challenges = update.challenges.sortedBy { it.name },
            validationErrorCodes = update.validationErrors.map { it.code.take(80) },
            recordedAt = now,
        )
    }

    private fun sanitizeValidationError(error: BrowserValidationError): BrowserValidationError {
        val message = error.message
            .replace(EMAIL, "<email>")
            .replace(URL, "<url>")
            .replace(PHONE, "<phone>")
            .take(300)
        return error.copy(message = message, code = error.code.take(80))
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private companion object {
        val EMAIL = Regex("(?i)\\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}\\b")
        val URL = Regex("(?i)https?://\\S+")
        val PHONE = Regex("(?<!\\w)\\+?[0-9][0-9 ()-]{7,}[0-9](?!\\w)")
    }
}
