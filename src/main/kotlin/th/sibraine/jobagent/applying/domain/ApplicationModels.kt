package th.sibraine.jobagent.applying.domain

import th.sibraine.jobagent.tailoring.domain.EvidenceRef
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

enum class ApplicationStatus { DRAFT, FILLING, NEEDS_INPUT, READY_TO_SUBMIT, SUBMITTED, FAILED }

enum class ApplicationRunStatus { RUNNING, NEEDS_INPUT, COMPLETED, FAILED }

enum class FormFieldType {
    TEXT, TEXTAREA, EMAIL, PHONE, NUMBER, DATE, URL, SELECT, RADIO, CHECKBOX, FILE, COMBOBOX,
}

/** What a form runner reported about a single field, before any answer is chosen. */
data class ObservedFormField(
    val fieldKey: String,
    val label: String,
    val type: FormFieldType = FormFieldType.TEXT,
    val required: Boolean = false,
    val placeholder: String? = null,
    val options: List<String> = emptyList(),
    val maxLength: Int? = null,
    val locator: String? = null,
    val page: String? = null,
)

enum class FormFieldTopic {
    FULL_NAME, FIRST_NAME, LAST_NAME, EMAIL, PHONE, LOCATION, LINKEDIN, GITHUB, WEBSITE,
    RESUME_FILE, COVER_LETTER,
    DESIRED_SALARY, CURRENT_SALARY, WORK_AUTHORIZATION, VISA_SPONSORSHIP, RELOCATION, REMOTE_PREFERENCE,
    NOTICE_PERIOD, START_DATE, YEARS_OF_EXPERIENCE,
    DEMOGRAPHIC, BACKGROUND_CHECK, REFERENCES, UNKNOWN,
}

/**
 * Topics whose answer may be taken from the confirmed resume and profile. Everything else needs an
 * explicit answer from settings, the reusable catalog or the user: the agent never derives salary,
 * work authorization, relocation or demographic answers from indirect data.
 */
val DERIVABLE_TOPICS = setOf(
    FormFieldTopic.FULL_NAME,
    FormFieldTopic.FIRST_NAME,
    FormFieldTopic.LAST_NAME,
    FormFieldTopic.EMAIL,
    FormFieldTopic.PHONE,
    FormFieldTopic.LOCATION,
    FormFieldTopic.LINKEDIN,
    FormFieldTopic.GITHUB,
    FormFieldTopic.WEBSITE,
    FormFieldTopic.RESUME_FILE,
)

/** Topics that carry personal or legal weight and are reported as such when the agent has to ask. */
val SENSITIVE_TOPICS = setOf(
    FormFieldTopic.DESIRED_SALARY,
    FormFieldTopic.CURRENT_SALARY,
    FormFieldTopic.WORK_AUTHORIZATION,
    FormFieldTopic.VISA_SPONSORSHIP,
    FormFieldTopic.RELOCATION,
    FormFieldTopic.DEMOGRAPHIC,
    FormFieldTopic.BACKGROUND_CHECK,
    FormFieldTopic.REFERENCES,
)

enum class AnswerSource { RESUME, PROFILE, SETTINGS, CATALOG, USER, DECLINED_BY_USER, ARTIFACT }

data class ApplicationAnswer(
    val fieldKey: String,
    val topic: FormFieldTopic,
    val question: String,
    val value: String?,
    val source: AnswerSource,
    val evidence: List<EvidenceRef> = emptyList(),
    val artifactId: UUID? = null,
)

enum class QuestionReason {
    NO_EXPLICIT_ANSWER, SENSITIVE_TOPIC, UNKNOWN_FIELD, OPTION_MISMATCH, VALUE_TOO_LONG, MISSING_ARTIFACT,
}

data class ApplicationQuestion(
    val fieldKey: String,
    val topic: FormFieldTopic,
    val question: String,
    val reason: QuestionReason,
    val required: Boolean,
    val options: List<String> = emptyList(),
    val catalogKey: String,
)

enum class ApprovalType { ANSWER, SUBMIT }

/** `REJECTED` is a user decision to decline; `SUPERSEDED` means the question itself is no longer current. */
enum class ApprovalStatus { PENDING, APPROVED, REJECTED, SUPERSEDED }

data class ApprovalRequest(
    val approvalId: UUID,
    val draftId: UUID,
    val type: ApprovalType,
    val status: ApprovalStatus,
    val question: String,
    val fieldKey: String? = null,
    val topic: FormFieldTopic? = null,
    val reason: QuestionReason? = null,
    val required: Boolean = false,
    val options: List<String> = emptyList(),
    val catalogKey: String? = null,
    val stateFingerprint: String? = null,
    val decisionValue: String? = null,
    val decisionNote: String? = null,
    val createdAt: Instant,
    val decidedAt: Instant? = null,
)

enum class ApplicationArtifactType { RESUME_PDF, COVER_LETTER, SCREENSHOT, SUBMISSION_RECEIPT }

data class ApplicationArtifact(
    val artifactId: UUID,
    val draftId: UUID,
    val type: ApplicationArtifactType,
    val fileName: String,
    val contentType: String,
    val sha256: String,
    val byteSize: Int,
    val createdAt: Instant,
)

data class ApplicationRun(
    val runId: UUID,
    val draftId: UUID,
    val status: ApplicationRunStatus,
    val formUrl: String? = null,
    val observedFields: List<ObservedFormField> = emptyList(),
    val filledFieldKeys: List<String> = emptyList(),
    val pendingFieldKeys: List<String> = emptyList(),
    val messages: List<String> = emptyList(),
    val idempotencyKey: String? = null,
    val requestFingerprint: String? = null,
    val startedAt: Instant,
    val finishedAt: Instant? = null,
)

enum class SubmissionMode { MANUAL, BROWSER, API }

data class ApplicationSubmission(
    val mode: SubmissionMode,
    val approvalId: UUID,
    val reference: String? = null,
    val note: String? = null,
    val submittedAt: Instant,
)

data class ApplicationDraft(
    val draftId: UUID,
    val version: Long,
    val candidateProfileId: UUID,
    val vacancyId: UUID,
    val resumeVariantId: UUID,
    val resumeVariantVersion: Long,
    val status: ApplicationStatus,
    val formUrl: String? = null,
    val observedFields: List<ObservedFormField> = emptyList(),
    val answers: List<ApplicationAnswer> = emptyList(),
    val stateFingerprint: String,
    val failureReason: String? = null,
    val submission: ApplicationSubmission? = null,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class ApplicationDraftView(
    val draft: ApplicationDraft,
    val pendingApprovals: List<ApprovalRequest> = emptyList(),
    val artifacts: List<ApplicationArtifact> = emptyList(),
    val latestRun: ApplicationRun? = null,
)

enum class SalaryPeriod { YEAR, MONTH, HOUR }

data class SalaryExpectation(
    val amount: BigDecimal,
    val currency: String,
    val period: SalaryPeriod = SalaryPeriod.YEAR,
    val negotiable: Boolean = false,
)

data class WorkAuthorization(val country: String, val status: String)

data class RelocationPreference(
    val willing: Boolean,
    val locations: List<String> = emptyList(),
    val notes: String? = null,
)

/**
 * Explicit application settings. Every field is opt-in: an absent value means the agent must ask
 * instead of guessing.
 */
data class ApplicationSettings(
    val desiredSalary: SalaryExpectation? = null,
    val workAuthorizations: List<WorkAuthorization> = emptyList(),
    val requiresSponsorship: Boolean? = null,
    val relocation: RelocationPreference? = null,
    val remotePreference: String? = null,
    val noticePeriod: String? = null,
    val earliestStartDate: String? = null,
)

data class AnswerCatalogEntry(
    val key: String,
    val question: String,
    val value: String,
    val topic: FormFieldTopic = FormFieldTopic.UNKNOWN,
    val updatedAt: Instant? = null,
)

/**
 * [approvalId] and [approvedStateFingerprint] carry the decision the workflow verified. A submitter
 * that presses a real Submit button checks the fingerprint against the draft once more, so an
 * approval of an older state can never reach the browser.
 */
data class SubmissionCommand(
    val draft: ApplicationDraft,
    val artifacts: List<ApplicationArtifact>,
    val approvalId: UUID,
    val approvedStateFingerprint: String,
    val reference: String? = null,
)

data class SubmissionReceipt(
    val mode: SubmissionMode,
    val reference: String? = null,
    val note: String? = null,
)

/** Replaceable seam for the browser runner of stage 6; the workflow itself stays runner-agnostic. */
interface ApplicationSubmitter {
    fun submit(command: SubmissionCommand): SubmissionReceipt
}
