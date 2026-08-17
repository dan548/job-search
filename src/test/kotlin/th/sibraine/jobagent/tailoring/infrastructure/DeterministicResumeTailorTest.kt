package th.sibraine.jobagent.tailoring.infrastructure

import th.sibraine.jobagent.candidate.domain.*
import th.sibraine.jobagent.matching.domain.MatchResult
import th.sibraine.jobagent.matching.domain.Recommendation
import th.sibraine.jobagent.matching.domain.VacancyAnalysis
import th.sibraine.jobagent.tailoring.domain.*
import th.sibraine.jobagent.vacancy.domain.Vacancy
import th.sibraine.jobagent.vacancy.domain.VacancySource
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class DeterministicResumeTailorTest {
    private val tailor = DeterministicResumeTailor()

    @Test
    fun `selects and orders bullets, projects and skills by vacancy relevance`() {
        val plan = tailor.tailor(request())

        assertEquals(
            listOf("ach-kotlin", "ach-kafka"),
            plan.experiences.single().achievements.map { it.sourceElementId },
        )
        assertEquals(listOf("prj-streaming"), plan.projects.map { it.sourceElementId })
        assertEquals(listOf("skill-kotlin", "skill-kafka", "skill-excel"), plan.skillElementIds)
    }

    @Test
    fun `reuses confirmed text verbatim with its own element as evidence`() {
        val bullet = tailor.tailor(request()).experiences.single().achievements.first()

        assertEquals("Wrote Kotlin services", bullet.text)
        assertEquals(listOf(EvidenceRef(EvidenceKind.RESUME_ELEMENT, "ach-kotlin")), bullet.evidence)
        assertEquals(listOf("Kotlin"), bullet.addressedRequirements)
        assertEquals("summary-1", tailor.tailor(request()).summary?.sourceElementId)
    }

    @Test
    fun `keeps every bullet when relevance cannot separate them`() {
        val resume = resume().let { base ->
            base.copy(
                experiences = listOf(
                    base.experiences.single().copy(
                        achievements = base.experiences.single().achievements.filter { it.elementId == "ach-office" }
                    )
                )
            )
        }

        val plan = tailor.tailor(request().copy(resume = resume))

        assertEquals(listOf("ach-office"), plan.experiences.single().achievements.map { it.sourceElementId })
    }

    private fun request() = TailoringRequest(
        vacancy = Vacancy(
            UUID.randomUUID(), VacancySource.MANUAL, null, null, "Acme", "Backend Engineer",
            "Kotlin and Kafka", null, null, null, null, null, null, Instant.EPOCH,
        ),
        analysis = VacancyAnalysis(
            role = "Backend Engineer",
            seniority = "Senior",
            requiredSkills = listOf("Kotlin"),
            preferredSkills = listOf("Kafka"),
        ),
        match = MatchResult(score = 70, recommendation = Recommendation.APPLY, reasoningSummary = "test"),
        profile = CandidateProfile(UUID.randomUUID(), GeneralInfo("Test Candidate")),
        resume = resume(),
    )

    private fun resume() = StructuredResume(
        summary = ResumeTextElement("summary-1", "Backend engineer", confirmed()),
        experiences = listOf(
            ResumeExperience(
                elementId = "exp-1",
                company = "Acme",
                role = "Backend Engineer",
                achievements = listOf(
                    ResumeTextElement("ach-office", "Organised the office move", confirmed()),
                    ResumeTextElement("ach-kotlin", "Wrote Kotlin services", confirmed()),
                    ResumeTextElement("ach-kafka", "Streamed events through Kafka", confirmed()),
                ),
                metadata = confirmed(),
            )
        ),
        projects = listOf(
            ResumeProject("prj-charity", "Charity website", metadata = confirmed()),
            ResumeProject("prj-streaming", "Kotlin event streaming", metadata = confirmed()),
        ),
        skills = listOf(
            ResumeSkill("skill-excel", "Excel", metadata = confirmed()),
            ResumeSkill("skill-kafka", "Kafka", metadata = confirmed()),
            ResumeSkill("skill-kotlin", "Kotlin", metadata = confirmed()),
        ),
    )

    private fun confirmed() = ResumeElementMetadata(reviewStatus = ResumeReviewStatus.CONFIRMED)
}
