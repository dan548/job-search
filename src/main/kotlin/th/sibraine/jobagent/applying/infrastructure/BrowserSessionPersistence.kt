package th.sibraine.jobagent.applying.infrastructure

import th.sibraine.jobagent.applying.domain.*
import jakarta.persistence.*
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant
import java.util.UUID

/**
 * The persisted browser session of one application draft. It outlives the live browser: after a
 * restart, a solved CAPTCHA or a manual sign-in the run continues from the checkpoint stored here.
 */
@Entity
@Table(name = "browser_sessions")
class BrowserSessionEntity(
    @Id
    @Column(name = "session_id", nullable = false)
    val sessionId: UUID,
    @Column(name = "draft_id", nullable = false, unique = true)
    val draftId: UUID,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: BrowserSessionStatus,
    @Column(name = "form_url", nullable = false, columnDefinition = "text")
    val formUrl: String,
    @Column(name = "current_url", nullable = false, columnDefinition = "text")
    var currentUrl: String,
    @Column(nullable = false, length = 64)
    var checkpoint: String,
    @Column(name = "observation_digest", nullable = false, length = 64)
    var observationDigest: String,
    @Column(name = "base_idempotency_key", nullable = false, length = 128)
    val baseIdempotencyKey: String,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    var challenges: List<BrowserChallenge> = emptyList(),
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "field_states", nullable = false, columnDefinition = "jsonb")
    var fieldStates: List<BrowserFieldState> = emptyList(),
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "validation_errors", nullable = false, columnDefinition = "jsonb")
    var validationErrors: List<BrowserValidationError> = emptyList(),
    @Enumerated(EnumType.STRING)
    @Column(name = "stop_reason", length = 40)
    var stopReason: BrowserStopReason? = null,
    @Column(name = "last_run_id")
    var lastRunId: UUID? = null,
    @Column(name = "resume_count", nullable = false)
    var resumeCount: Int = 0,
    @Column(name = "storage_state", columnDefinition = "text")
    var storageState: String? = null,
    @Column(name = "confirmation_reference", columnDefinition = "text")
    var confirmationReference: String? = null,
    @Column(name = "failure_code", length = 120)
    var failureCode: String? = null,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant,
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant,
) {
    fun toDomain() = BrowserSession(
        sessionId = sessionId,
        draftId = draftId,
        status = status,
        formUrl = formUrl,
        currentUrl = currentUrl,
        checkpoint = checkpoint,
        observationDigest = observationDigest,
        baseIdempotencyKey = baseIdempotencyKey,
        challenges = challenges.toSet(),
        fieldStates = fieldStates,
        validationErrors = validationErrors,
        stopReason = stopReason,
        lastRunId = lastRunId,
        resumeCount = resumeCount,
        restorable = storageState != null,
        confirmationReference = confirmationReference,
        failureCode = failureCode,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}

@Entity
@Table(name = "browser_diagnostic_snapshots")
@SequenceGenerator(
    name = "browser_diagnostic_snapshot_generator",
    sequenceName = "browser_diagnostic_snapshot_seq",
    allocationSize = 1,
)
class BrowserDiagnosticSnapshotEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "browser_diagnostic_snapshot_generator")
    @Column(name = "snapshot_seq")
    var sequence: Long = 0,
    @Column(name = "draft_id", nullable = false)
    val draftId: UUID,
    @Column(name = "run_id")
    val runId: UUID? = null,
    @Column(nullable = false, columnDefinition = "text")
    val origin: String,
    @Column(name = "path_hash", nullable = false, length = 64)
    val pathHash: String,
    @Column(nullable = false, length = 64)
    val checkpoint: String,
    @Column(name = "observation_digest", nullable = false, length = 64)
    val observationDigest: String,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    val fields: List<BrowserDiagnosticField>,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    val challenges: List<BrowserChallenge>,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "validation_error_codes", nullable = false, columnDefinition = "jsonb")
    val validationErrorCodes: List<String>,
    @Column(name = "recorded_at", nullable = false)
    val recordedAt: Instant,
) {
    fun toDomain() = BrowserDiagnosticSnapshot(
        sequence = sequence,
        draftId = draftId,
        runId = runId,
        origin = origin,
        pathHash = pathHash,
        checkpoint = checkpoint,
        observationDigest = observationDigest,
        fields = fields,
        challenges = challenges.toSet(),
        validationErrorCodes = validationErrorCodes,
        recordedAt = recordedAt,
    )
}

/** Append-only trail. Field keys and detail codes only: no answer value, no file content. */
@Entity
@Table(name = "browser_audit_entries")
@SequenceGenerator(
    name = "browser_audit_entry_generator",
    sequenceName = "browser_audit_entry_seq",
    allocationSize = 1,
)
class BrowserAuditEntryEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "browser_audit_entry_generator")
    @Column(name = "entry_seq")
    var sequence: Long = 0,
    @Column(name = "draft_id", nullable = false)
    val draftId: UUID,
    @Column(name = "run_id")
    val runId: UUID? = null,
    @Column(nullable = false, length = 40)
    val event: String,
    @Column(name = "field_key", length = 255)
    val fieldKey: String? = null,
    @Column(name = "detail_code", length = 120)
    val detailCode: String? = null,
    @Column(length = 64)
    val checkpoint: String? = null,
    @Column(name = "recorded_at", nullable = false)
    val recordedAt: Instant,
) {
    fun toDomain() = BrowserAuditRecord(
        sequence = sequence,
        draftId = draftId,
        runId = runId,
        event = event,
        fieldKey = fieldKey,
        detailCode = detailCode,
        checkpoint = checkpoint,
        recordedAt = recordedAt,
    )
}

interface BrowserSessionJpaRepository : JpaRepository<BrowserSessionEntity, UUID> {
    fun findByDraftId(draftId: UUID): BrowserSessionEntity?
}

interface BrowserAuditEntryJpaRepository : JpaRepository<BrowserAuditEntryEntity, Long> {
    fun findByDraftIdOrderBySequenceAsc(draftId: UUID): List<BrowserAuditEntryEntity>
}

interface BrowserDiagnosticSnapshotJpaRepository : JpaRepository<BrowserDiagnosticSnapshotEntity, Long> {
    fun findByDraftIdOrderBySequenceAsc(draftId: UUID): List<BrowserDiagnosticSnapshotEntity>
}
