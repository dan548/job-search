package th.sibraine.jobagent.candidate.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StructuredResumeMergerTest {
    private val confirmed = ResumeElementMetadata(reviewStatus = ResumeReviewStatus.CONFIRMED)

    @Test
    fun `enriches canonical resume with older detail without replacing current positioning`() {
        val current = StructuredResume(
            identity = ResumeIdentity("identity-current", "Ada Lovelace", "Staff Engineer", confirmed),
            experiences = listOf(
                ResumeExperience(
                    "experience-current", "Acme", "Backend Engineer",
                    startDate = ResumeDate(2020),
                    description = "Built services",
                    achievements = listOf(ResumeTextElement("achievement-current", "Led platform work", confirmed)),
                    metadata = confirmed,
                )
            ),
            skills = listOf(ResumeSkill("skill-kotlin", "Kotlin", metadata = confirmed)),
        )
        val older = StructuredResume(
            identity = ResumeIdentity("identity-old", "Ada Lovelace", "Junior Engineer", confirmed),
            experiences = listOf(
                ResumeExperience(
                    "experience-old", "Acme", "Backend Engineer",
                    startDate = ResumeDate(2020),
                    description = "Built and operated distributed backend services for payments",
                    achievements = listOf(ResumeTextElement("achievement-old", "Reduced latency by 30%", confirmed)),
                    metadata = confirmed,
                )
            ),
            skills = listOf(
                ResumeSkill("skill-kotlin-old", "Kotlin", metadata = confirmed),
                ResumeSkill("skill-kafka", "Kafka", metadata = confirmed),
            ),
        )

        val merged = StructuredResumeMerger().enrich(current, older)

        assertEquals("Staff Engineer", merged.identity?.headline)
        assertEquals(listOf("experience-current"), merged.experiences.map { it.elementId })
        assertEquals(
            listOf("Led platform work", "Reduced latency by 30%"),
            merged.experiences.single().achievements.map { it.text },
        )
        assertEquals("Built and operated distributed backend services for payments", merged.experiences.single().description)
        assertEquals(listOf("Kotlin", "Kafka"), merged.skills.map { it.name })
        assertTrue(merged.elementRefs().all { it.metadata.reviewStatus == ResumeReviewStatus.CONFIRMED })
        StructuredResumeValidator().validate(merged, requireReviewed = true)
    }
}
