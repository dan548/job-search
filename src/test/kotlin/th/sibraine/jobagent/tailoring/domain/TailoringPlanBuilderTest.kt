package th.sibraine.jobagent.tailoring.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import th.sibraine.jobagent.candidate.domain.CandidateProfile
import th.sibraine.jobagent.candidate.domain.GeneralInfo
import th.sibraine.jobagent.candidate.domain.StructuredResume
import th.sibraine.jobagent.matching.domain.MatchResult
import th.sibraine.jobagent.matching.domain.Recommendation
import th.sibraine.jobagent.matching.domain.RequirementEvidenceRow
import th.sibraine.jobagent.matching.domain.RequirementImportance
import th.sibraine.jobagent.matching.domain.RequirementSource
import th.sibraine.jobagent.matching.domain.RequirementStatus
import java.util.UUID

class TailoringPlanBuilderTest {
    private val builder = TailoringPlanBuilder()

    @Test
    fun `groups related requirements into actionable themes and separates preferences`() {
        val result = build(
            row("Kotlin/JVM"),
            row("JVM ecosystem: Gradle, dependency management"),
            row("6+ years of experience with Java and Kotlin"),
            row("Remote"),
            row("Contractor"),
            row("Tbilisi / Belgrade / Lisbon"),
            row("SQL (SQLite, MySQL, PostgreSQL)"),
        )

        assertEquals(4, result.questions.size)
        assertEquals(4, result.gapGroups.size)
        val jvm = result.questions.single { it.requirement == "Java, Kotlin и экосистема JVM" }
        assertEquals(TailoringQuestionKind.EVIDENCE, jvm.kind)
        assertEquals(3, jvm.relatedRequirements.size)

        val workFormat = result.questions.single { it.requirement == "Формат работы" }
        assertEquals(TailoringQuestionKind.PREFERENCE, workFormat.kind)
        assertEquals(listOf("Remote", "Contractor"), workFormat.relatedRequirements)

        assertTrue(result.questions.any { it.requirement == "SQL и базы данных" })
        assertEquals(
            listOf("Remote", "Contractor"),
            result.gapGroups.single { it.title == "Формат работы" }.requirements,
        )
    }

    @Test
    fun `only asks about hard requirements or blockers`() {
        val result = build(
            row("Kafka", RequirementImportance.SOFT_REQUIREMENT, RequirementStatus.MISSING),
            row("Security clearance", RequirementImportance.SOFT_REQUIREMENT, RequirementStatus.BLOCKED),
        )

        assertEquals(listOf("Security clearance"), result.questions.map { it.requirement })
    }

    @Test
    fun `group exposes the strongest status and importance without losing requirements`() {
        val result = build(
            row("Kotlin/JVM", RequirementImportance.SOFT_REQUIREMENT, RequirementStatus.UNASSESSED),
            row("JVM debugging", RequirementImportance.HARD_REQUIREMENT, RequirementStatus.MISSING),
            row("Gradle", RequirementImportance.SOFT_REQUIREMENT, RequirementStatus.BLOCKED),
        )

        val group = result.gapGroups.single()
        assertEquals(RequirementStatus.BLOCKED, group.status)
        assertEquals(RequirementImportance.HARD_REQUIREMENT, group.importance)
        assertEquals(3, group.requirements.size)
    }

    @Test
    fun `keeps preference actions visible when there are many evidence themes`() {
        val evidence = (1..10).map { row("Distinct requirement $it") }

        val result = build(*(evidence + row("Remote")).toTypedArray())

        assertEquals(8, result.questions.size)
        assertTrue(result.questions.any { it.kind == TailoringQuestionKind.PREFERENCE })
    }

    @Test
    fun `carries a saved decision into a rebuilt plan`() {
        val decision = TailoringGapDecision(
            TailoringGapDecisionType.NOT_APPLICABLE,
            "Не относится к этой роли",
            decidedAt = java.time.Instant.parse("2026-08-18T08:00:00Z"),
        )
        val resume = StructuredResume()

        val result = builder.build(
            plan = TailoringPlan(),
            base = resume,
            confirmed = resume,
            tailored = resume,
            profile = CandidateProfile(UUID.randomUUID(), GeneralInfo("Candidate")),
            match = MatchResult(
                score = 0,
                recommendation = Recommendation.MAYBE,
                reasoningSummary = "",
                requirementEvidenceMatrix = listOf(row("Kotlin/JVM")),
            ),
            decisions = mapOf("jvm" to decision),
        )

        assertEquals(decision, result.gapGroups.single().decision)
        assertEquals(decision, result.questions.single().decision)
    }

    private fun build(vararg rows: RequirementEvidenceRow): TailoringPlan {
        val resume = StructuredResume()
        return builder.build(
            plan = TailoringPlan(),
            base = resume,
            confirmed = resume,
            tailored = resume,
            profile = CandidateProfile(UUID.randomUUID(), GeneralInfo("Candidate")),
            match = MatchResult(
                score = 0,
                recommendation = Recommendation.MAYBE,
                reasoningSummary = "",
                requirementEvidenceMatrix = rows.toList(),
            ),
        )
    }

    private fun row(
        requirement: String,
        importance: RequirementImportance = RequirementImportance.HARD_REQUIREMENT,
        status: RequirementStatus = RequirementStatus.MISSING,
    ) = RequirementEvidenceRow(
        requirement = requirement,
        importance = importance,
        sources = listOf(RequirementSource.HARD_REQUIREMENT),
        status = status,
    )
}
