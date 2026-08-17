package th.sibraine.jobagent.tailoring.domain

import th.sibraine.jobagent.candidate.domain.CandidateProfile
import th.sibraine.jobagent.candidate.domain.StructuredResume

class InvalidTailoringPlanException(message: String) : RuntimeException(message)

class TailoringPlanValidator {
    fun validate(plan: TailoringPlan, resume: StructuredResume, profile: CandidateProfile) {
        val evidence = EvidenceIndex(resume, profile)

        plan.summary?.let { summary ->
            validateText(summary, "summary", setOfNotNull(resume.summary?.elementId), evidence)
        }

        requireDistinct(plan.experiences.map { it.sourceElementId }, "experience")
        plan.experiences.forEach { experience ->
            val source = resume.experiences.firstOrNull { it.elementId == experience.sourceElementId }
                ?: throw InvalidTailoringPlanException(
                    "Unknown or unconfirmed experience: ${experience.sourceElementId}"
                )
            val allowed = source.achievements.map { it.elementId }.toSet()
            requireDistinct(experience.achievements.mapNotNull { it.sourceElementId }, "experience achievement")
            experience.achievements.forEach { validateText(it, "experience achievement", allowed, evidence) }
        }

        requireDistinct(plan.projects.map { it.sourceElementId }, "project")
        plan.projects.forEach { project ->
            val source = resume.projects.firstOrNull { it.elementId == project.sourceElementId }
                ?: throw InvalidTailoringPlanException("Unknown or unconfirmed project: ${project.sourceElementId}")
            val allowed = source.achievements.map { it.elementId }.toSet()
            requireDistinct(project.achievements.mapNotNull { it.sourceElementId }, "project achievement")
            project.achievements.forEach { validateText(it, "project achievement", allowed, evidence) }
        }

        requireDistinct(plan.skillElementIds, "skill")
        val unknownSkills = plan.skillElementIds - resume.skills.map { it.elementId }.toSet()
        if (unknownSkills.isNotEmpty()) {
            throw InvalidTailoringPlanException(
                "Unknown or unconfirmed skills: ${unknownSkills.sorted().joinToString()}"
            )
        }
    }

    private fun validateText(
        value: TailoredText,
        kind: String,
        allowedSources: Set<String>,
        evidence: EvidenceIndex,
    ) {
        if (value.text.isBlank()) {
            throw InvalidTailoringPlanException("Tailored $kind must not be blank")
        }
        value.sourceElementId?.let { sourceElementId ->
            if (sourceElementId !in allowedSources) {
                throw InvalidTailoringPlanException("Tailored $kind cites a foreign source element: $sourceElementId")
            }
        }
        if (value.evidence.isEmpty()) {
            throw InvalidTailoringPlanException("Tailored $kind has no evidence: ${value.text}")
        }
        value.evidence.forEach { ref ->
            evidence.textOrNull(ref) ?: throw InvalidTailoringPlanException(
                "Unknown or unconfirmed evidence: ${ref.kind} ${ref.id}"
            )
        }
        val unsupported = numbers(value.text) - numbers(evidence.sourceText(value.evidence))
        if (unsupported.isNotEmpty()) {
            throw InvalidTailoringPlanException(
                "Tailored $kind states values missing from its evidence: ${unsupported.sorted().joinToString()}"
            )
        }
    }

    private fun requireDistinct(ids: List<String>, kind: String) {
        if (ids.distinct().size != ids.size) {
            throw InvalidTailoringPlanException("Tailored plan reuses the same $kind source element")
        }
    }

    private fun numbers(value: String): Set<String> = NUMBER.findAll(value)
        .map { it.value.replace(',', '.') }
        .map { if (it.contains('.')) it.trimEnd('0').trimEnd('.') else it }
        .toSet()

    private companion object {
        private val NUMBER = Regex("\\d+(?:[.,]\\d+)?")
    }
}
