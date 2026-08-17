package th.sibraine.jobagent.matching.domain

import th.sibraine.jobagent.candidate.domain.CandidateFact
import th.sibraine.jobagent.candidate.domain.CandidateProfile
import th.sibraine.jobagent.candidate.domain.FactType
import th.sibraine.jobagent.candidate.domain.GeneralInfo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.UUID

class RequirementEvidenceMatrixBuilderTest {
    private val builder = RequirementEvidenceMatrixBuilder()

    @Test
    fun `builds auditable rows and does not expose unverified facts`() {
        val profile = CandidateProfile(
            UUID.randomUUID(),
            GeneralInfo("Candidate"),
            facts = listOf(
                CandidateFact("verified", FactType.SKILL, "Built services with Kotlin", true),
                CandidateFact("draft", FactType.SKILL, "Used Kafka", false),
            ),
        )
        val analysis = VacancyAnalysis(
            role = "Backend Engineer",
            seniority = null,
            requiredSkills = listOf("Kotlin", "PostgreSQL"),
            preferredSkills = listOf("Kafka"),
            niceToHave = listOf("kafka"),
            locationConstraints = listOf("Remote in EU"),
        )
        val match = MatchResult(
            score = 60,
            recommendation = Recommendation.MAYBE,
            matchedRequirements = listOf(MatchedRequirement("kotlin", 0.9, listOf("verified", "draft"))),
            missingRequirements = listOf(
                MissingRequirement("PostgreSQL", RequirementImportance.HARD_REQUIREMENT),
            ),
            reasoningSummary = "Partial match",
        )

        val rows = builder.build(profile, analysis, match)

        assertEquals(4, rows.size)
        val kotlin = rows.single { it.requirement == "Kotlin" }
        val postgres = rows.single { it.requirement == "PostgreSQL" }
        val kafka = rows.single { it.requirement == "Kafka" }
        val location = rows.single { it.requirement == "Remote in EU" }
        assertEquals(RequirementStatus.MATCHED, kotlin.status)
        assertEquals(listOf("verified"), kotlin.evidence.map { it.factId })
        assertEquals(RequirementStatus.MISSING, postgres.status)
        assertEquals(RequirementImportance.SOFT_REQUIREMENT, kafka.importance)
        assertEquals(
            listOf(RequirementSource.PREFERRED_SKILL, RequirementSource.NICE_TO_HAVE),
            kafka.sources,
        )
        assertEquals(RequirementStatus.UNASSESSED, location.status)
    }

    @Test
    fun `marks a hard blocker explicitly`() {
        val profile = CandidateProfile(UUID.randomUUID(), GeneralInfo("Candidate"))
        val analysis = VacancyAnalysis(
            role = "Engineer",
            seniority = null,
            workAuthorizationConstraints = listOf("EU work authorization"),
        )
        val match = MatchResult(
            score = 0,
            recommendation = Recommendation.REJECT,
            hardBlockers = listOf("EU work authorization"),
            reasoningSummary = "Blocked",
        )

        assertEquals(RequirementStatus.BLOCKED, builder.build(profile, analysis, match).single().status)
    }
}
