package th.sibraine.jobagent.applying.infrastructure

import th.sibraine.jobagent.applying.domain.*
import jakarta.persistence.*
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "application_drafts")
@SequenceGenerator(
    name = "application_draft_version_generator",
    sequenceName = "application_draft_version_seq",
    allocationSize = 1,
)
class ApplicationDraftEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "application_draft_version_generator")
    var version: Long = 0,
    @Column(name = "draft_id", nullable = false, unique = true)
    val draftId: UUID,
    @Column(name = "candidate_profile_id", nullable = false)
    val candidateProfileId: UUID,
    @Column(name = "vacancy_id", nullable = false)
    val vacancyId: UUID,
    @Column(name = "resume_variant_id", nullable = false)
    val resumeVariantId: UUID,
    @Column(name = "resume_variant_version", nullable = false)
    val resumeVariantVersion: Long,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: ApplicationStatus,
    @Column(name = "form_url", columnDefinition = "text")
    var formUrl: String? = null,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "observed_fields", nullable = false, columnDefinition = "jsonb")
    var observedFields: List<ObservedFormField> = emptyList(),
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    var answers: List<ApplicationAnswer> = emptyList(),
    @Column(name = "state_fingerprint", nullable = false, length = 64)
    var stateFingerprint: String,
    @Column(name = "failure_reason", columnDefinition = "text")
    var failureReason: String? = null,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    var submission: ApplicationSubmission? = null,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant,
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant,
) {
    fun toDomain() = ApplicationDraft(
        draftId = draftId,
        version = version,
        candidateProfileId = candidateProfileId,
        vacancyId = vacancyId,
        resumeVariantId = resumeVariantId,
        resumeVariantVersion = resumeVariantVersion,
        status = status,
        formUrl = formUrl,
        observedFields = observedFields,
        answers = answers,
        stateFingerprint = stateFingerprint,
        failureReason = failureReason,
        submission = submission,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}

@Entity
@Table(name = "application_runs")
class ApplicationRunEntity(
    @Id
    @Column(name = "run_id", nullable = false)
    val runId: UUID,
    @Column(name = "draft_id", nullable = false)
    val draftId: UUID,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: ApplicationRunStatus,
    @Column(name = "form_url", columnDefinition = "text")
    val formUrl: String? = null,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "observed_fields", nullable = false, columnDefinition = "jsonb")
    val observedFields: List<ObservedFormField> = emptyList(),
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "filled_field_keys", nullable = false, columnDefinition = "jsonb")
    var filledFieldKeys: List<String> = emptyList(),
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "pending_field_keys", nullable = false, columnDefinition = "jsonb")
    var pendingFieldKeys: List<String> = emptyList(),
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    var messages: List<String> = emptyList(),
    @Column(name = "idempotency_key", length = 128)
    val idempotencyKey: String? = null,
    @Column(name = "request_fingerprint", length = 64)
    val requestFingerprint: String? = null,
    @Column(name = "started_at", nullable = false)
    val startedAt: Instant,
    @Column(name = "finished_at")
    var finishedAt: Instant? = null,
) {
    fun toDomain() = ApplicationRun(
        runId = runId,
        draftId = draftId,
        status = status,
        formUrl = formUrl,
        observedFields = observedFields,
        filledFieldKeys = filledFieldKeys,
        pendingFieldKeys = pendingFieldKeys,
        messages = messages,
        idempotencyKey = idempotencyKey,
        requestFingerprint = requestFingerprint,
        startedAt = startedAt,
        finishedAt = finishedAt,
    )
}

@Entity
@Table(name = "application_approvals")
class ApplicationApprovalEntity(
    @Id
    @Column(name = "approval_id", nullable = false)
    val approvalId: UUID,
    @Column(name = "draft_id", nullable = false)
    val draftId: UUID,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    val type: ApprovalType,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: ApprovalStatus,
    @Column(nullable = false, columnDefinition = "text")
    val question: String,
    @Column(name = "field_key", length = 255)
    val fieldKey: String? = null,
    @Enumerated(EnumType.STRING)
    @Column(length = 40)
    val topic: FormFieldTopic? = null,
    @Enumerated(EnumType.STRING)
    @Column(length = 40)
    val reason: QuestionReason? = null,
    @Column(name = "is_required", nullable = false)
    val required: Boolean = false,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    val options: List<String> = emptyList(),
    @Column(name = "catalog_key", length = 160)
    val catalogKey: String? = null,
    @Column(name = "state_fingerprint", length = 64)
    val stateFingerprint: String? = null,
    @Column(name = "decision_value", columnDefinition = "text")
    var decisionValue: String? = null,
    @Column(name = "decision_note", columnDefinition = "text")
    var decisionNote: String? = null,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant,
    @Column(name = "decided_at")
    var decidedAt: Instant? = null,
) {
    fun toDomain() = ApprovalRequest(
        approvalId = approvalId,
        draftId = draftId,
        type = type,
        status = status,
        question = question,
        fieldKey = fieldKey,
        topic = topic,
        reason = reason,
        required = required,
        options = options,
        catalogKey = catalogKey,
        stateFingerprint = stateFingerprint,
        decisionValue = decisionValue,
        decisionNote = decisionNote,
        createdAt = createdAt,
        decidedAt = decidedAt,
    )
}

@Entity
@Table(name = "application_artifacts")
class ApplicationArtifactEntity(
    @Id
    @Column(name = "artifact_id", nullable = false)
    val artifactId: UUID,
    @Column(name = "draft_id", nullable = false)
    val draftId: UUID,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    val type: ApplicationArtifactType,
    @Column(name = "file_name", nullable = false, length = 255)
    val fileName: String,
    @Column(name = "content_type", nullable = false, length = 100)
    val contentType: String,
    @Column(nullable = false, length = 64)
    val sha256: String,
    @Column(name = "byte_size", nullable = false)
    val byteSize: Int,
    @Column(nullable = false, columnDefinition = "bytea")
    val content: ByteArray,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant,
) {
    fun toDomain() = ApplicationArtifact(
        artifactId = artifactId,
        draftId = draftId,
        type = type,
        fileName = fileName,
        contentType = contentType,
        sha256 = sha256,
        byteSize = byteSize,
        createdAt = createdAt,
    )
}

@Entity
@Table(name = "application_settings")
class ApplicationSettingsEntity(
    @Id
    @Column(name = "candidate_profile_id", nullable = false)
    val candidateProfileId: UUID,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    var settings: ApplicationSettings,
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant,
)

@Entity
@Table(name = "application_answer_catalog")
class AnswerCatalogEntity(
    @Id
    @Column(nullable = false)
    val id: UUID,
    @Column(name = "candidate_profile_id", nullable = false)
    val candidateProfileId: UUID,
    @Column(name = "answer_key", nullable = false, length = 160)
    val answerKey: String,
    @Column(nullable = false, columnDefinition = "text")
    var question: String,
    @Column(name = "answer_value", nullable = false, columnDefinition = "text")
    var value: String,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    var topic: FormFieldTopic,
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant,
) {
    fun toDomain() = AnswerCatalogEntry(
        key = answerKey,
        question = question,
        value = value,
        topic = topic,
        updatedAt = updatedAt,
    )
}

interface ApplicationDraftJpaRepository : JpaRepository<ApplicationDraftEntity, Long> {
    fun findByDraftId(draftId: UUID): ApplicationDraftEntity?
    fun findFirstByVacancyIdOrderByVersionDesc(vacancyId: UUID): ApplicationDraftEntity?
    fun findFirstByCandidateProfileIdAndVacancyIdOrderByVersionDesc(
        candidateProfileId: UUID,
        vacancyId: UUID,
    ): ApplicationDraftEntity?
    fun findFirstByCandidateProfileIdAndVacancyIdAndStatusNotInOrderByVersionDesc(
        candidateProfileId: UUID,
        vacancyId: UUID,
        statuses: Collection<ApplicationStatus>,
    ): ApplicationDraftEntity?
}

interface ApplicationRunJpaRepository : JpaRepository<ApplicationRunEntity, UUID> {
    fun findFirstByDraftIdOrderByStartedAtDesc(draftId: UUID): ApplicationRunEntity?
    fun findByDraftIdOrderByStartedAtDesc(draftId: UUID): List<ApplicationRunEntity>
    fun findByDraftIdAndIdempotencyKey(draftId: UUID, idempotencyKey: String): ApplicationRunEntity?
}

interface ApplicationApprovalJpaRepository : JpaRepository<ApplicationApprovalEntity, UUID> {
    fun findByDraftIdOrderByCreatedAtAsc(draftId: UUID): List<ApplicationApprovalEntity>
}

interface ApplicationArtifactJpaRepository : JpaRepository<ApplicationArtifactEntity, UUID> {
    fun findByDraftIdOrderByCreatedAtAsc(draftId: UUID): List<ApplicationArtifactEntity>
    fun findFirstByDraftIdAndType(draftId: UUID, type: ApplicationArtifactType): ApplicationArtifactEntity?
}

interface ApplicationSettingsJpaRepository : JpaRepository<ApplicationSettingsEntity, UUID>

interface AnswerCatalogJpaRepository : JpaRepository<AnswerCatalogEntity, UUID> {
    fun findByCandidateProfileIdOrderByAnswerKeyAsc(candidateProfileId: UUID): List<AnswerCatalogEntity>
    fun findByCandidateProfileIdAndAnswerKey(candidateProfileId: UUID, answerKey: String): AnswerCatalogEntity?
}
