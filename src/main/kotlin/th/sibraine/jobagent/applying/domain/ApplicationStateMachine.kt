package th.sibraine.jobagent.applying.domain

import java.security.MessageDigest
import java.util.UUID

class InvalidApplicationTransitionException(message: String) : RuntimeException(message)

/**
 * `DRAFT → FILLING → NEEDS_INPUT → READY_TO_SUBMIT → SUBMITTED/FAILED`.
 *
 * `SUBMITTED` and `FAILED` are terminal. A new run may always pull a draft back to `FILLING`, so a
 * form that grew a new question after an approval cannot slip into submission unnoticed.
 */
class ApplicationStateMachine {
    fun require(from: ApplicationStatus, to: ApplicationStatus): ApplicationStatus {
        if (to !in (ALLOWED[from] ?: emptySet())) {
            throw InvalidApplicationTransitionException("Application cannot move from $from to $to")
        }
        return to
    }

    fun requireActive(status: ApplicationStatus) {
        if (status in TERMINAL) {
            throw InvalidApplicationTransitionException("Application is already $status")
        }
    }

    fun statusAfter(resolution: AnswerResolution): ApplicationStatus =
        if (resolution.blockingQuestions.isEmpty()) ApplicationStatus.READY_TO_SUBMIT
        else ApplicationStatus.NEEDS_INPUT

    /**
     * Identity of everything a submit approval covers. Any later change to the resume variant, an
     * answer or an attached artifact invalidates an approval granted for an older state.
     */
    fun fingerprint(
        resumeVariantId: UUID,
        answers: List<ApplicationAnswer>,
        artifacts: List<ApplicationArtifact>,
    ): String {
        val payload = buildString {
            append("variant=").append(resumeVariantId).append('\n')
            answers.sortedBy { it.fieldKey }.forEach { answer ->
                append(answer.fieldKey).append('=')
                append(answer.source).append(':')
                append(answer.value.orEmpty()).append('\n')
            }
            artifacts.map { it.sha256 }.sorted().forEach { append("artifact=").append(it).append('\n') }
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(payload.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    private companion object {
        private val TERMINAL = setOf(ApplicationStatus.SUBMITTED, ApplicationStatus.FAILED)

        private val ALLOWED: Map<ApplicationStatus, Set<ApplicationStatus>> = mapOf(
            ApplicationStatus.DRAFT to setOf(ApplicationStatus.FILLING, ApplicationStatus.FAILED),
            ApplicationStatus.FILLING to setOf(
                ApplicationStatus.FILLING,
                ApplicationStatus.NEEDS_INPUT,
                ApplicationStatus.READY_TO_SUBMIT,
                ApplicationStatus.FAILED,
            ),
            ApplicationStatus.NEEDS_INPUT to setOf(
                ApplicationStatus.FILLING,
                ApplicationStatus.NEEDS_INPUT,
                ApplicationStatus.READY_TO_SUBMIT,
                ApplicationStatus.FAILED,
            ),
            ApplicationStatus.READY_TO_SUBMIT to setOf(
                ApplicationStatus.FILLING,
                ApplicationStatus.NEEDS_INPUT,
                ApplicationStatus.READY_TO_SUBMIT,
                ApplicationStatus.SUBMITTED,
                ApplicationStatus.FAILED,
            ),
            ApplicationStatus.SUBMITTED to emptySet(),
            ApplicationStatus.FAILED to emptySet(),
        )
    }
}
