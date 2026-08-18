package th.sibraine.jobagent.matching.domain

import th.sibraine.jobagent.candidate.domain.FactType

data class VacancyAnalysis(
    val role: String,
    val seniority: String?,
    val requiredSkills: List<String> = emptyList(),
    val preferredSkills: List<String> = emptyList(),
    val responsibilities: List<String> = emptyList(),
    val hardRequirements: List<String> = emptyList(),
    val softRequirements: List<String> = emptyList(),
    val niceToHave: List<String> = emptyList(),
    val recruiterFluff: List<String> = emptyList(),
    val languageRequirements: List<String> = emptyList(),
    val locationConstraints: List<String> = emptyList(),
    val workAuthorizationConstraints: List<String> = emptyList(),
    val salary: SalaryRange? = null,
)

data class SalaryRange(val from: String?, val to: String?, val currency: String?)

data class MatchResult(
    val score: Int,
    val recommendation: Recommendation,
    val matchedRequirements: List<MatchedRequirement> = emptyList(),
    val missingRequirements: List<MissingRequirement> = emptyList(),
    val hardBlockers: List<String> = emptyList(),
    val reasoningSummary: String,
    val requirementEvidenceMatrix: List<RequirementEvidenceRow> = emptyList(),
)

data class MatchedRequirement(
    val requirement: String,
    val strength: Double,
    val evidenceFactIds: List<String>,
)

data class MissingRequirement(val requirement: String, val importance: RequirementImportance)

data class RequirementEvidenceRow(
    val requirement: String,
    val importance: RequirementImportance,
    val sources: List<RequirementSource>,
    val status: RequirementStatus,
    val matchStrength: Double? = null,
    val evidence: List<EvidenceFact> = emptyList(),
    val relatedRequirements: List<String> = emptyList(),
)

data class EvidenceFact(
    val factId: String,
    val type: FactType,
    val text: String,
)

enum class RequirementSource {
    REQUIRED_SKILL,
    PREFERRED_SKILL,
    HARD_REQUIREMENT,
    SOFT_REQUIREMENT,
    NICE_TO_HAVE,
    LANGUAGE,
    LOCATION,
    WORK_AUTHORIZATION,
    MATCH_OUTPUT,
}

enum class RequirementStatus { MATCHED, MISSING, BLOCKED, UNASSESSED }

enum class RequirementImportance { HARD_REQUIREMENT, SOFT_REQUIREMENT, NICE_TO_HAVE }
enum class Recommendation { REJECT, MAYBE, APPLY, PRIORITY }
