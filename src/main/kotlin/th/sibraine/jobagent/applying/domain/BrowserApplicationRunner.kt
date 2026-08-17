package th.sibraine.jobagent.applying.domain

import java.util.UUID

enum class BrowserChallenge { CAPTCHA, OTP, REAUTHENTICATION }

data class BrowserInspectCommand(
    val draftId: UUID,
    val formUrl: String,
    val idempotencyKey: String,
    /** Rebuilds a session that is no longer live before the page is observed again. */
    val resume: BrowserSessionState? = null,
)

data class BrowserFormSnapshot(
    val url: String,
    val fields: List<ObservedFormField>,
    val checkpoint: String,
    val challenges: Set<BrowserChallenge> = emptySet(),
)

data class BrowserArtifactPayload(
    val artifactId: UUID,
    val fileName: String,
    val contentType: String,
    val content: ByteArray,
) {
    override fun toString(): String =
        "BrowserArtifactPayload(artifactId=$artifactId, fileName=$fileName, contentType=$contentType, content=<redacted>)"
}

/** A short-lived command. Values and file bytes must never be copied into audit messages. */
data class BrowserFillAction(
    val fieldKey: String,
    val locator: String,
    val type: FormFieldType,
    val value: String? = null,
    val checked: Boolean? = null,
    val artifact: BrowserArtifactPayload? = null,
) {
    override fun toString(): String =
        "BrowserFillAction(fieldKey=$fieldKey, locator=$locator, type=$type, value=<redacted>, " +
            "checked=<redacted>, artifact=${artifact?.artifactId})"
}

data class BrowserFillCommand(
    val draftId: UUID,
    val runId: UUID,
    val idempotencyKey: String,
    val expectedCheckpoint: String,
    val actions: List<BrowserFillAction>,
)

data class BrowserValidationError(
    val fieldKey: String?,
    val message: String,
    /** Stable, value-free code suitable for persistence and metrics. */
    val code: String = "INVALID_VALUE",
)

data class BrowserFillResult(
    val checkpoint: String,
    val appliedFieldKeys: List<String>,
    val validationErrors: List<BrowserValidationError> = emptyList(),
    val challenges: Set<BrowserChallenge> = emptySet(),
)

data class BrowserSubmitCommand(
    val draftId: UUID,
    val runId: UUID,
    val idempotencyKey: String,
    val expectedCheckpoint: String,
    val approvedStateFingerprint: String,
)

/**
 * Replaceable browser seam. A Playwright/CDP implementation may live outside the application
 * workflow; tests and other browser engines can implement exactly the same protocol.
 *
 * Implementations must make [fill] and [submit] idempotent by command idempotency key and reject a
 * stale checkpoint instead of acting on a page that changed since inspection.
 *
 * [inspect] doubles as the re-scan of an already open page: it reports the form as it looks now, so a
 * step of a multi-page questionnaire, a field that appeared after filling and a page the user touched
 * by hand are all observed the same way.
 */
interface BrowserApplicationRunner {
    fun inspect(command: BrowserInspectCommand): BrowserFormSnapshot
    fun fill(command: BrowserFillCommand): BrowserFillResult
    fun submit(command: BrowserSubmitCommand): SubmissionReceipt

    /** State that would let [inspect] rebuild this session later, or `null` when it cannot be exported. */
    fun exportState(draftId: UUID): BrowserSessionState? = null

    /** Drops the live session; a later [inspect] starts from the persisted state instead. */
    fun release(draftId: UUID) {}
}
