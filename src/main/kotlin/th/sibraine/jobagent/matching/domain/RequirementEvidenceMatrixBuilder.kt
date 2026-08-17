package th.sibraine.jobagent.matching.domain

import th.sibraine.jobagent.candidate.domain.CandidateProfile

class RequirementEvidenceMatrixBuilder {
    fun build(
        profile: CandidateProfile,
        analysis: VacancyAnalysis,
        result: MatchResult,
    ): List<RequirementEvidenceRow> {
        val requirements = linkedMapOf<String, RequirementDraft>()

        add(requirements, analysis.requiredSkills, RequirementImportance.HARD_REQUIREMENT, RequirementSource.REQUIRED_SKILL)
        add(requirements, analysis.hardRequirements, RequirementImportance.HARD_REQUIREMENT, RequirementSource.HARD_REQUIREMENT)
        add(requirements, analysis.languageRequirements, RequirementImportance.HARD_REQUIREMENT, RequirementSource.LANGUAGE)
        add(requirements, analysis.locationConstraints, RequirementImportance.HARD_REQUIREMENT, RequirementSource.LOCATION)
        add(
            requirements,
            analysis.workAuthorizationConstraints,
            RequirementImportance.HARD_REQUIREMENT,
            RequirementSource.WORK_AUTHORIZATION,
        )
        add(requirements, analysis.preferredSkills, RequirementImportance.SOFT_REQUIREMENT, RequirementSource.PREFERRED_SKILL)
        add(requirements, analysis.softRequirements, RequirementImportance.SOFT_REQUIREMENT, RequirementSource.SOFT_REQUIREMENT)
        add(requirements, analysis.niceToHave, RequirementImportance.NICE_TO_HAVE, RequirementSource.NICE_TO_HAVE)
        result.matchedRequirements.forEach {
            add(requirements, listOf(it.requirement), RequirementImportance.SOFT_REQUIREMENT, RequirementSource.MATCH_OUTPUT)
        }
        result.missingRequirements.forEach {
            add(requirements, listOf(it.requirement), it.importance, RequirementSource.MATCH_OUTPUT)
        }

        val matches = result.matchedRequirements.associateBy { normalize(it.requirement) }
        val missing = result.missingRequirements.associateBy { normalize(it.requirement) }
        val blockers = result.hardBlockers.map(::normalize).toSet()
        val verifiedFacts = profile.facts.filter { it.verified }.associateBy { it.factId }

        return requirements.map { (key, draft) ->
            val match = matches[key]
            val isBlocked = key in blockers
            val status = when {
                isBlocked -> RequirementStatus.BLOCKED
                match != null -> RequirementStatus.MATCHED
                key in missing -> RequirementStatus.MISSING
                else -> RequirementStatus.UNASSESSED
            }
            RequirementEvidenceRow(
                requirement = draft.requirement,
                importance = draft.importance,
                sources = draft.sources.toList(),
                status = status,
                matchStrength = match?.strength,
                evidence = match?.evidenceFactIds.orEmpty().mapNotNull(verifiedFacts::get).map {
                    EvidenceFact(it.factId, it.type, it.text)
                },
            )
        }
    }

    private fun add(
        requirements: LinkedHashMap<String, RequirementDraft>,
        values: List<String>,
        importance: RequirementImportance,
        source: RequirementSource,
    ) {
        values.filter { it.isNotBlank() }.forEach { requirement ->
            val key = normalize(requirement)
            val existing = requirements[key]
            if (existing == null) {
                requirements[key] = RequirementDraft(requirement, importance, linkedSetOf(source))
            } else {
                existing.sources += source
                if (importance.priority > existing.importance.priority) existing.importance = importance
            }
        }
    }

    private fun normalize(value: String): String = value.lowercase()
        .replace("springboot", "spring boot")
        .replace("restful", "rest")
        .replace(Regex("[^a-zа-я0-9+#]+"), " ")
        .trim()

    private val RequirementImportance.priority: Int
        get() = when (this) {
            RequirementImportance.HARD_REQUIREMENT -> 3
            RequirementImportance.SOFT_REQUIREMENT -> 2
            RequirementImportance.NICE_TO_HAVE -> 1
        }

    private data class RequirementDraft(
        val requirement: String,
        var importance: RequirementImportance,
        val sources: LinkedHashSet<RequirementSource>,
    )
}
