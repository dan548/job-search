package th.sibraine.jobagent.matching.domain

data class MatchResultChange(
    val scoreBefore: Int,
    val scoreAfter: Int,
    val newlyConfirmed: List<RequirementAssessmentChange> = emptyList(),
    val stillWithoutEvidence: List<String> = emptyList(),
)

data class RequirementAssessmentChange(
    val requirement: String,
    val evidence: List<EvidenceFact> = emptyList(),
)

class MatchResultChangeBuilder {
    fun build(previous: MatchResult?, current: MatchResult): MatchResultChange? {
        if (previous == null) return null
        val previousByRequirement = previous.requirementEvidenceMatrix.associateBy { normalize(it.requirement) }
        val newlyConfirmed = current.requirementEvidenceMatrix
            .filter { row ->
                row.status == RequirementStatus.MATCHED &&
                    previousByRequirement[normalize(row.requirement)]?.status != RequirementStatus.MATCHED
            }
            .map { RequirementAssessmentChange(it.requirement, it.evidence) }
        val stillWithoutEvidence = current.requirementEvidenceMatrix
            .filter { it.status != RequirementStatus.MATCHED && it.evidence.isEmpty() }
            .map { it.requirement }
            .distinct()
        return MatchResultChange(previous.score, current.score, newlyConfirmed, stillWithoutEvidence)
    }

    private fun normalize(value: String): String = value.lowercase().trim().replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .replace(Regex("\\s+"), " ")
}
