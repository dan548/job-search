package th.sibraine.jobagent.matching.infrastructure

import th.sibraine.jobagent.candidate.domain.*
import th.sibraine.jobagent.matching.domain.Recommendation
import th.sibraine.jobagent.matching.domain.VacancyAnalysis
import th.sibraine.jobagent.vacancy.domain.Vacancy
import th.sibraine.jobagent.vacancy.domain.VacancySource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class FakeCandidateMatcherTest {
    @Test
    fun `work authorization mismatch becomes hard blocker and reject`() {
        val profile = CandidateProfile(
            UUID.randomUUID(), GeneralInfo("Candidate"),
            facts = listOf(CandidateFact("fact-1", FactType.SKILL, "Kotlin", true)),
        )
        val result = FakeCandidateMatcher().match(
            profile,
            Vacancy(UUID.randomUUID(), VacancySource.MANUAL, null, null, "Acme", "Engineer", "", null,
                null, null, null, null, null, Instant.now()),
            VacancyAnalysis("Engineer", null, requiredSkills = listOf("Kotlin"),
                workAuthorizationConstraints = listOf("EU work authorization")),
        )
        assertEquals(Recommendation.REJECT, result.recommendation)
        assertEquals(listOf("EU work authorization"), result.hardBlockers)
        assertEquals(0, result.score)
    }
}
