package th.sibraine.jobagent.applying.domain

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.util.UUID

class ApplicationStateMachineTest {
    private val stateMachine = ApplicationStateMachine()
    private val variantId = UUID.randomUUID()

    @Test
    fun `walks the workflow and refuses to skip filling or to leave a terminal state`() {
        assertEquals(
            ApplicationStatus.FILLING,
            stateMachine.require(ApplicationStatus.DRAFT, ApplicationStatus.FILLING),
        )
        assertEquals(
            ApplicationStatus.NEEDS_INPUT,
            stateMachine.require(ApplicationStatus.FILLING, ApplicationStatus.NEEDS_INPUT),
        )
        assertEquals(
            ApplicationStatus.SUBMITTED,
            stateMachine.require(ApplicationStatus.READY_TO_SUBMIT, ApplicationStatus.SUBMITTED),
        )

        assertThrows<InvalidApplicationTransitionException> {
            stateMachine.require(ApplicationStatus.DRAFT, ApplicationStatus.SUBMITTED)
        }
        assertThrows<InvalidApplicationTransitionException> {
            stateMachine.require(ApplicationStatus.NEEDS_INPUT, ApplicationStatus.SUBMITTED)
        }
        assertThrows<InvalidApplicationTransitionException> {
            stateMachine.require(ApplicationStatus.SUBMITTED, ApplicationStatus.FILLING)
        }
        assertThrows<InvalidApplicationTransitionException> {
            stateMachine.requireActive(ApplicationStatus.FAILED)
        }
    }

    @Test
    fun `required questions keep the draft in NEEDS_INPUT, optional ones do not`() {
        val optional = AnswerResolution(questions = listOf(question("q1", required = false)))
        val required = AnswerResolution(questions = listOf(question("q2", required = true)))

        assertEquals(ApplicationStatus.READY_TO_SUBMIT, stateMachine.statusAfter(AnswerResolution()))
        assertEquals(ApplicationStatus.READY_TO_SUBMIT, stateMachine.statusAfter(optional))
        assertEquals(ApplicationStatus.NEEDS_INPUT, stateMachine.statusAfter(required))
    }

    @Test
    fun `fingerprint changes with any answer, artifact or variant change`() {
        val answers = listOf(answer("email", "ada@example.com"))
        val artifacts = listOf(artifact("a".repeat(64)))
        val base = stateMachine.fingerprint(variantId, answers, artifacts)

        assertEquals(base, stateMachine.fingerprint(variantId, answers.reversed(), artifacts))
        assertNotEquals(base, stateMachine.fingerprint(variantId, answers + answer("city", "Warsaw"), artifacts))
        assertNotEquals(base, stateMachine.fingerprint(variantId, listOf(answer("email", "other@x.io")), artifacts))
        assertNotEquals(base, stateMachine.fingerprint(variantId, answers, listOf(artifact("b".repeat(64)))))
        assertNotEquals(base, stateMachine.fingerprint(UUID.randomUUID(), answers, artifacts))
    }

    private fun question(fieldKey: String, required: Boolean) = ApplicationQuestion(
        fieldKey = fieldKey,
        topic = FormFieldTopic.UNKNOWN,
        question = "Question $fieldKey",
        reason = QuestionReason.UNKNOWN_FIELD,
        required = required,
        catalogKey = "QUESTION:$fieldKey",
    )

    private fun answer(fieldKey: String, value: String) = ApplicationAnswer(
        fieldKey = fieldKey,
        topic = FormFieldTopic.UNKNOWN,
        question = "Question $fieldKey",
        value = value,
        source = AnswerSource.USER,
    )

    private fun artifact(sha256: String) = ApplicationArtifact(
        artifactId = UUID.randomUUID(),
        draftId = UUID.randomUUID(),
        type = ApplicationArtifactType.RESUME_PDF,
        fileName = "resume.pdf",
        contentType = "application/pdf",
        sha256 = sha256,
        byteSize = 10,
        createdAt = Instant.EPOCH,
    )
}
