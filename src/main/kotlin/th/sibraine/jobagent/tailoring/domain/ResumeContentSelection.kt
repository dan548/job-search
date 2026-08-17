package th.sibraine.jobagent.tailoring.domain

import th.sibraine.jobagent.candidate.domain.*
import java.security.MessageDigest
import java.util.Locale

data class ResumeSelectionOption(
    val key: String,
    val title: String,
    val detail: String? = null,
    val selectedByDefault: Boolean = true,
)

data class ResumeContentSelection(
    val contacts: List<ResumeSelectionOption>,
    val experiences: List<ResumeSelectionOption>,
    val skills: List<ResumeSelectionOption>,
)

data class ResumeContentSelectionRequest(
    val contactElementIds: List<String>? = null,
    val experienceElementIds: List<String>? = null,
    val skillElementIds: List<String>? = null,
)

class CanonicalResumeAssembler {
    fun assemble(profile: CandidateProfile, imported: StructuredResume?): StructuredResume {
        val base = imported?.confirmedOnly() ?: StructuredResume()
        val existingSkills = base.skills.associateBy { normalize(it.name) }
        val skills = profile.skills.map { name ->
            existingSkills[normalize(name)] ?: ResumeSkill(
                elementId = stableId("manual-skill", name),
                name = name,
                metadata = manualMetadata(name),
            )
        }.ifEmpty { base.skills }
        return base.copy(
            identity = ResumeIdentity(
                elementId = base.identity?.elementId ?: stableId("profile-identity", profile.id.toString()),
                fullName = profile.generalInfo.displayName,
                headline = profile.generalInfo.headline,
                metadata = manualMetadata(profile.generalInfo.displayName),
            ),
            contacts = profile.contacts.ifEmpty { base.contacts },
            experiences = profile.experiences.ifEmpty { base.experiences },
            skills = skills,
        )
    }

    fun options(resume: StructuredResume, vacancyText: String = ""): ResumeContentSelection {
        val defaultContacts = resume.contacts.groupBy { it.type }.values.mapNotNull { it.firstOrNull() }
            .map { it.elementId }.toSet()
        val vacancyTokens = tokens(vacancyText)
        val relevantExperiences = resume.experiences.filter { experience ->
            tokens(listOfNotNull(experience.role, experience.company, experience.description).joinToString(" "))
                .any { it in vacancyTokens }
        }.map { it.elementId }.toSet().ifEmpty { resume.experiences.map { it.elementId }.toSet() }
        val relevantSkills = resume.skills.filter { skill ->
            normalize(vacancyText).contains(normalize(skill.name))
        }.map { it.elementId }.toSet().ifEmpty { resume.skills.map { it.elementId }.toSet() }
        return ResumeContentSelection(
            contacts = resume.contacts.map {
                ResumeSelectionOption(it.elementId, contactTitle(it.type), it.value, it.elementId in defaultContacts)
            },
            experiences = resume.experiences.map {
                ResumeSelectionOption(
                    it.elementId,
                    "${it.role} — ${it.company}",
                    listOfNotNull(date(it.startDate), if (it.current) "по настоящее время" else date(it.endDate)).joinToString(" — "),
                    selectedByDefault = it.elementId in relevantExperiences,
                )
            },
            skills = resume.skills.map { ResumeSelectionOption(it.elementId, it.name, selectedByDefault = it.elementId in relevantSkills) },
        )
    }

    fun select(resume: StructuredResume, request: ResumeContentSelectionRequest?): StructuredResume {
        if (request == null) return resume
        val contacts = request.contactElementIds?.toSet()
        val experiences = request.experienceElementIds?.toSet()
        val skills = request.skillElementIds?.toSet()
        return resume.copy(
            contacts = contacts?.let { selected -> resume.contacts.filter { it.elementId in selected } } ?: resume.contacts,
            experiences = experiences?.let { selected -> resume.experiences.filter { it.elementId in selected } } ?: resume.experiences,
            skills = skills?.let { selected -> resume.skills.filter { it.elementId in selected } } ?: resume.skills,
            projects = resume.projects.map { project ->
                project.copy(skillElementIds = project.skillElementIds.filter { id -> skills == null || id in skills })
            },
        )
    }

    private fun contactTitle(type: ResumeContactType) = when (type) {
        ResumeContactType.EMAIL -> "Email"
        ResumeContactType.PHONE -> "Телефон"
        ResumeContactType.LOCATION -> "Локация"
        ResumeContactType.WEBSITE -> "Сайт"
        ResumeContactType.LINKEDIN -> "LinkedIn"
        ResumeContactType.GITHUB -> "GitHub"
        ResumeContactType.OTHER -> "Другой контакт"
    }

    private fun date(value: ResumeDate?) = value?.let { date ->
        date.month?.let { "%02d.%04d".format(it, date.year) } ?: date.year.toString()
    }

    private fun manualMetadata(source: String) = ResumeElementMetadata(
        provenance = ResumeProvenance(source),
        confidence = 1.0,
        reviewStatus = ResumeReviewStatus.CONFIRMED,
    )

    private fun stableId(prefix: String, value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(normalize(value).toByteArray())
            .take(8).joinToString("") { "%02x".format(it) }
        return "$prefix-$digest"
    }

    private fun normalize(value: String) = value.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")
    private fun tokens(value: String) = normalize(value).split(Regex("[^\\p{L}\\p{N}+#.]+"))
        .filter { it.length >= 3 }.toSet()
}
