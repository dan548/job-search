package th.sibraine.jobagent.matching.domain

import th.sibraine.jobagent.candidate.domain.CandidateProfile

class InvalidMatchResultException(message: String) : RuntimeException(message)

class MatchResultValidator {
    fun validate(profile: CandidateProfile, result: MatchResult) {
        if (result.score !in 0..100) {
            throw InvalidMatchResultException("Score must be between 0 and 100")
        }
        if (result.hardBlockers.isNotEmpty() && result.recommendation != Recommendation.REJECT) {
            throw InvalidMatchResultException("A result with hard blockers must be REJECT")
        }

        val knownFacts = profile.facts.filter { it.verified }.map { it.factId }.toSet()
        result.matchedRequirements.forEach { match ->
            if (match.evidenceFactIds.isEmpty()) {
                throw InvalidMatchResultException("Matched requirement '${match.requirement}' has no evidence")
            }
            val unknown = match.evidenceFactIds - knownFacts
            if (unknown.isNotEmpty()) {
                throw InvalidMatchResultException(
                    "Unknown or unverified evidence fact IDs: ${unknown.sorted().joinToString()}"
                )
            }
            if (match.strength !in 0.0..1.0) {
                throw InvalidMatchResultException("Match strength must be between 0 and 1")
            }
        }
    }
}
