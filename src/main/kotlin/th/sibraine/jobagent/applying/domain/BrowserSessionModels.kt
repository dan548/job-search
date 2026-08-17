package th.sibraine.jobagent.applying.domain

import java.time.Instant
import java.util.UUID

enum class BrowserSessionStatus { ACTIVE, PAUSED, SUBMITTED, CLOSED }

enum class BrowserFieldStatus { OBSERVED, PLANNED, APPLIED, PENDING_INPUT, SKIPPED, VALIDATION_ERROR }

enum class BrowserStopReason {
    CHALLENGE, VALIDATION_ERRORS, PENDING_ANSWERS, RESCAN_LIMIT, RUNNER_ERROR, SUBMIT_ERROR,
}

/** Per-control progress. Answer values are deliberately never part of this model. */
data class BrowserFieldState(
    val fieldKey: String,
    val status: BrowserFieldStatus,
    val detailCode: String? = null,
)

/** A PII-free diagnostic view of one observed page. */
data class BrowserDiagnosticField(
    val fieldKeyHash: String,
    val type: FormFieldType,
    val required: Boolean,
    val status: BrowserFieldStatus,
)

data class BrowserDiagnosticSnapshot(
    val sequence: Long,
    val draftId: UUID,
    val runId: UUID?,
    val origin: String,
    val pathHash: String,
    val checkpoint: String,
    val observationDigest: String,
    val fields: List<BrowserDiagnosticField>,
    val challenges: Set<BrowserChallenge>,
    val validationErrorCodes: List<String>,
    val recordedAt: Instant,
)

/**
 * Everything a runner needs to rebuild a browser session that is no longer live: the page the run
 * stopped on and an engine-specific storage state.
 *
 * [storageState] carries the cookies and local storage of a signed-in ATS session. It is written to
 * PostgreSQL only when explicitly enabled, never rendered into audit and never returned by the API.
 */
data class BrowserSessionState(
    val currentUrl: String,
    val storageState: String? = null,
) {
    override fun toString(): String = "BrowserSessionState(currentUrl=$currentUrl, " +
        "storageState=${if (storageState == null) "absent" else "<redacted>"})"
}

/** The persisted checkpoint of a browser session, as exposed to the API. */
data class BrowserSession(
    val sessionId: UUID,
    val draftId: UUID,
    val status: BrowserSessionStatus,
    val formUrl: String,
    val currentUrl: String,
    val checkpoint: String,
    val observationDigest: String,
    val baseIdempotencyKey: String,
    val challenges: Set<BrowserChallenge> = emptySet(),
    val fieldStates: List<BrowserFieldState> = emptyList(),
    val validationErrors: List<BrowserValidationError> = emptyList(),
    val stopReason: BrowserStopReason? = null,
    val lastRunId: UUID? = null,
    val resumeCount: Int = 0,
    /** Whether a stored storage state allows continuing this session after a backend restart. */
    val restorable: Boolean = false,
    val confirmationReference: String? = null,
    val failureCode: String? = null,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class BrowserAuditRecord(
    val sequence: Long,
    val draftId: UUID,
    val runId: UUID?,
    val event: String,
    val fieldKey: String? = null,
    val detailCode: String? = null,
    val checkpoint: String? = null,
    val recordedAt: Instant,
)
