package th.sibraine.jobagent.candidate.domain

import java.util.Locale

/**
 * Adds confirmed information from another resume to the current canonical resume without
 * downgrading the current identity, summary, or already confirmed detail.
 */
class StructuredResumeMerger {
    fun enrich(current: StructuredResume, addition: StructuredResume): StructuredResume {
        val base = current.confirmedOnly()
        val extra = addition.confirmedOnly()
        val skills = mergeBy(base.skills, extra.skills) { normalize(it.name) }
        val canonicalSkillIds = skills.associateBy({ normalize(it.name) }, { it.elementId })

        return StructuredResume(
            schemaVersion = base.schemaVersion,
            identity = base.identity ?: extra.identity,
            summary = base.summary ?: extra.summary,
            contacts = mergeBy(base.contacts, extra.contacts) { "${it.type}:${normalize(it.value)}" },
            experiences = mergeExperiences(base.experiences, extra.experiences),
            projects = mergeProjects(base.projects, extra.projects, extra.skills, canonicalSkillIds),
            education = mergeBy(base.education, extra.education) {
                listOf(normalize(it.institution), normalize(it.degree.orEmpty()), dateKey(it.endDate)).joinToString("|")
            },
            certifications = mergeBy(base.certifications, extra.certifications) {
                "${normalize(it.name)}|${normalize(it.issuer.orEmpty())}"
            },
            languages = mergeBy(base.languages, extra.languages) { normalize(it.name) },
            skills = skills,
        )
    }

    private fun mergeExperiences(
        current: List<ResumeExperience>,
        addition: List<ResumeExperience>,
    ): List<ResumeExperience> {
        val additions = addition.groupBy(::experienceKey)
        val currentKeys = current.map(::experienceKey).toSet()
        return current.map { experience ->
            additions[experienceKey(experience)].orEmpty().fold(experience) { merged, extra ->
                merged.copy(
                    location = merged.location ?: extra.location,
                    startDate = merged.startDate ?: extra.startDate,
                    endDate = merged.endDate ?: extra.endDate,
                    current = merged.current || extra.current,
                    description = richer(merged.description, extra.description),
                    achievements = mergeBy(merged.achievements, extra.achievements) { normalize(it.text) },
                    technologies = (merged.technologies + extra.technologies).distinctBy(::normalize),
                )
            }
        } + addition.filter { experienceKey(it) !in currentKeys }
    }

    private fun mergeProjects(
        current: List<ResumeProject>,
        addition: List<ResumeProject>,
        additionSkills: List<ResumeSkill>,
        canonicalSkillIds: Map<String, String>,
    ): List<ResumeProject> {
        val additionSkillNames = additionSkills.associateBy({ it.elementId }, { normalize(it.name) })
        val remapped = addition.map { project ->
            project.copy(
                skillElementIds = project.skillElementIds.mapNotNull { id ->
                    additionSkillNames[id]?.let(canonicalSkillIds::get)
                }.distinct()
            )
        }
        val additions = remapped.groupBy { normalize(it.name) }
        val currentKeys = current.map { normalize(it.name) }.toSet()
        return current.map { project ->
            additions[normalize(project.name)].orEmpty().fold(project) { merged, extra ->
                merged.copy(
                    description = richer(merged.description, extra.description),
                    url = merged.url ?: extra.url,
                    achievements = mergeBy(merged.achievements, extra.achievements) { normalize(it.text) },
                    skillElementIds = (merged.skillElementIds + extra.skillElementIds).distinct(),
                )
            }
        } + remapped.filter { normalize(it.name) !in currentKeys }
    }

    private fun experienceKey(value: ResumeExperience): String = listOf(
        normalize(value.company),
        normalize(value.role),
        dateKey(value.startDate),
    ).joinToString("|")

    private fun richer(current: String?, addition: String?): String? = when {
        current.isNullOrBlank() -> addition
        addition.isNullOrBlank() -> current
        addition.length > current.length -> addition
        else -> current
    }

    private fun <T : Any> mergeBy(current: List<T>, addition: List<T>, key: (T) -> String): List<T> {
        val seenKeys = current.mapTo(linkedSetOf(), key)
        val seenIds = current.mapNotNullTo(linkedSetOf()) { elementId(it) }
        return current + addition.filter { value ->
            val id = elementId(value)
            seenKeys.add(key(value)) && (id == null || seenIds.add(id))
        }
    }

    private fun elementId(value: Any): String? = when (value) {
        is ResumeContact -> value.elementId
        is ResumeExperience -> value.elementId
        is ResumeProject -> value.elementId
        is ResumeEducation -> value.elementId
        is ResumeCertification -> value.elementId
        is ResumeLanguage -> value.elementId
        is ResumeSkill -> value.elementId
        is ResumeTextElement -> value.elementId
        else -> null
    }

    private fun dateKey(value: ResumeDate?): String = value?.let { "${it.year}-${it.month ?: 0}" }.orEmpty()

    private fun normalize(value: String): String = value.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")
}
