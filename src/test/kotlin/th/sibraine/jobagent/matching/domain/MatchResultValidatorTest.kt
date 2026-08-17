package th.sibraine.jobagent.matching.domain

import th.sibraine.jobagent.candidate.domain.*
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.UUID

class MatchResultValidatorTest {
    private val validator = MatchResultValidator()
    private val profile = CandidateProfile(
        UUID.randomUUID(), GeneralInfo("Candidate"),
        facts = listOf(CandidateFact("fact-001", FactType.SKILL, "Kotlin", true)),
    )

    @Test
    fun `accepts valid result`() = assertDoesNotThrow {
        validator.validate(profile, result(score = 80))
    }

    @Test
    fun `rejects score outside range`() {
        assertThrows<InvalidMatchResultException> { validator.validate(profile, result(score = 101)) }
        assertThrows<InvalidMatchResultException> { validator.validate(profile, result(score = -1)) }
    }

    @Test
    fun `rejects unknown fact id`() {
        val invalid = result().copy(
            matchedRequirements = listOf(MatchedRequirement("Kotlin", 1.0, listOf("fact-999")))
        )
        assertThrows<InvalidMatchResultException> { validator.validate(profile, invalid) }
    }

    @Test
    fun `rejects match with empty evidence`() {
        val invalid = result().copy(
            matchedRequirements = listOf(MatchedRequirement("Kotlin", 1.0, emptyList()))
        )
        assertThrows<InvalidMatchResultException> { validator.validate(profile, invalid) }
    }

    @Test
    fun `rejects unverified evidence fact id`() {
        val unverifiedProfile = profile.copy(
            facts = profile.facts + CandidateFact("fact-002", FactType.SKILL, "Kafka", false)
        )
        val invalid = result().copy(
            matchedRequirements = listOf(MatchedRequirement("Kafka", 1.0, listOf("fact-002")))
        )
        assertThrows<InvalidMatchResultException> { validator.validate(unverifiedProfile, invalid) }
    }

    @Test
    fun `hard blocker requires reject recommendation`() {
        val invalid = result().copy(recommendation = Recommendation.APPLY, hardBlockers = listOf("Visa required"))
        assertThrows<InvalidMatchResultException> { validator.validate(profile, invalid) }
    }

    private fun result(score: Int = 80) = MatchResult(
        score, Recommendation.APPLY,
        matchedRequirements = listOf(MatchedRequirement("Kotlin", 1.0, listOf("fact-001"))),
        reasoningSummary = "evidence-backed",
    )
}
