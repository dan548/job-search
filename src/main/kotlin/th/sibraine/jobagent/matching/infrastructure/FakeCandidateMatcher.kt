package th.sibraine.jobagent.matching.infrastructure

import th.sibraine.jobagent.candidate.domain.CandidateFact
import th.sibraine.jobagent.candidate.domain.CandidateProfile
import th.sibraine.jobagent.matching.domain.*
import th.sibraine.jobagent.vacancy.domain.Vacancy
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

@Component
@ConditionalOnProperty(
    prefix = "job-agent.ai.openai",
    name = ["enabled"],
    havingValue = "false",
    matchIfMissing = true,
)
class FakeCandidateMatcher : CandidateMatcher {
    override fun match(profile: CandidateProfile, vacancy: Vacancy, analysis: VacancyAnalysis): MatchResult {
        val verifiedFacts = profile.facts.filter { it.verified }
        val required = analysis.requiredSkills.distinctBy(::normalize)
        val preferred = analysis.preferredSkills.distinctBy(::normalize)
        val matchedRequired = required.mapNotNull { match(it, verifiedFacts) }
        val matchedPreferred = preferred.mapNotNull { match(it, verifiedFacts) }
        val matchedNames = (matchedRequired + matchedPreferred).map { normalize(it.requirement) }.toSet()
        val missing = required.filter { normalize(it) !in matchedNames }.map {
            MissingRequirement(it, RequirementImportance.HARD_REQUIREMENT)
        } + preferred.filter { normalize(it) !in matchedNames }.map {
            MissingRequirement(it, RequirementImportance.NICE_TO_HAVE)
        }

        val hardBlockers = findHardBlockers(profile, analysis)
        val requiredCoverage = if (required.isEmpty()) 1.0 else matchedRequired.size.toDouble() / required.size
        val preferredCoverage = if (preferred.isEmpty()) 1.0 else matchedPreferred.size.toDouble() / preferred.size
        val score = if (hardBlockers.isNotEmpty()) 0 else (requiredCoverage * 75 + preferredCoverage * 25).toInt()
        val recommendation = when {
            hardBlockers.isNotEmpty() -> Recommendation.REJECT
            score >= 90 -> Recommendation.PRIORITY
            score >= 70 -> Recommendation.APPLY
            score >= 45 -> Recommendation.MAYBE
            else -> Recommendation.REJECT
        }

        return MatchResult(
            score = score,
            recommendation = recommendation,
            matchedRequirements = matchedRequired + matchedPreferred,
            missingRequirements = missing,
            hardBlockers = hardBlockers,
            reasoningSummary = "Matched ${matchedRequired.size}/${required.size} required and " +
                "${matchedPreferred.size}/${preferred.size} preferred skills using verified profile facts.",
        )
    }

    private fun match(requirement: String, facts: List<CandidateFact>): MatchedRequirement? {
        val expected = normalize(requirement)
        val evidence = facts.filter { normalize(it.text).contains(expected) }.map { it.factId }
        return evidence.takeIf { it.isNotEmpty() }?.let { MatchedRequirement(requirement, 1.0, it) }
    }

    private fun findHardBlockers(profile: CandidateProfile, analysis: VacancyAnalysis): List<String> =
        analysis.workAuthorizationConstraints.filter { constraint ->
            profile.constraints.none { normalize(it).contains(normalize(constraint)) }
        }

    private fun normalize(value: String): String = value.lowercase()
        .replace("springboot", "spring boot")
        .replace("restful", "rest")
        .replace(Regex("[^a-zа-я0-9+#]+"), " ")
        .trim()
}
