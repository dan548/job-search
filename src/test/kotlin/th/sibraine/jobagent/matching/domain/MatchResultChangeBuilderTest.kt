package th.sibraine.jobagent.matching.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import th.sibraine.jobagent.candidate.domain.FactType

class MatchResultChangeBuilderTest {
    private val builder = MatchResultChangeBuilder()

    @Test
    fun `reports newly confirmed requirements and remaining evidence gaps`() {
        val previous = result(
            42,
            row("Kotlin / JVM", RequirementStatus.MISSING),
            row("Kafka", RequirementStatus.MISSING),
        )
        val evidence = EvidenceFact("fact-kotlin", FactType.EXPERIENCE, "Разрабатывал сервисы на Kotlin")
        val current = result(
            58,
            row("Kotlin/JVM", RequirementStatus.MATCHED, evidence),
            row("Kafka", RequirementStatus.MISSING),
            row("PostgreSQL", RequirementStatus.UNASSESSED),
        )

        val change = requireNotNull(builder.build(previous, current))

        assertEquals(42, change.scoreBefore)
        assertEquals(58, change.scoreAfter)
        assertEquals(listOf("Kotlin/JVM"), change.newlyConfirmed.map { it.requirement })
        assertEquals(listOf(evidence), change.newlyConfirmed.single().evidence)
        assertEquals(listOf("Kafka", "PostgreSQL"), change.stillWithoutEvidence)
    }

    @Test
    fun `does not invent a comparison for the first analysis`() {
        assertNull(builder.build(null, result(50, row("Kotlin", RequirementStatus.MATCHED))))
    }

    private fun result(score: Int, vararg rows: RequirementEvidenceRow) = MatchResult(
        score = score,
        recommendation = Recommendation.MAYBE,
        reasoningSummary = "",
        requirementEvidenceMatrix = rows.toList(),
    )

    private fun row(
        requirement: String,
        status: RequirementStatus,
        vararg evidence: EvidenceFact,
    ) = RequirementEvidenceRow(
        requirement = requirement,
        importance = RequirementImportance.HARD_REQUIREMENT,
        sources = listOf(RequirementSource.HARD_REQUIREMENT),
        status = status,
        evidence = evidence.toList(),
    )
}
