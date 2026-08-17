package th.sibraine.jobagent.candidate.domain

data class StructuredResume(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val identity: ResumeIdentity? = null,
    val summary: ResumeTextElement? = null,
    val contacts: List<ResumeContact> = emptyList(),
    val experiences: List<ResumeExperience> = emptyList(),
    val projects: List<ResumeProject> = emptyList(),
    val education: List<ResumeEducation> = emptyList(),
    val certifications: List<ResumeCertification> = emptyList(),
    val languages: List<ResumeLanguage> = emptyList(),
    val skills: List<ResumeSkill> = emptyList(),
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
    }
}

data class ResumeIdentity(
    val elementId: String,
    val fullName: String,
    val headline: String? = null,
    val metadata: ResumeElementMetadata = ResumeElementMetadata(),
)

data class ResumeTextElement(
    val elementId: String,
    val text: String,
    val metadata: ResumeElementMetadata = ResumeElementMetadata(),
)

data class ResumeContact(
    val elementId: String,
    val type: ResumeContactType,
    val value: String,
    val label: String? = null,
    val metadata: ResumeElementMetadata = ResumeElementMetadata(),
)

enum class ResumeContactType { EMAIL, PHONE, LOCATION, WEBSITE, LINKEDIN, GITHUB, OTHER }

data class ResumeExperience(
    val elementId: String,
    val company: String,
    val role: String,
    val location: String? = null,
    val startDate: ResumeDate? = null,
    val endDate: ResumeDate? = null,
    val current: Boolean = false,
    val description: String? = null,
    val achievements: List<ResumeTextElement> = emptyList(),
    val metadata: ResumeElementMetadata = ResumeElementMetadata(),
    val technologies: List<String> = emptyList(),
)

data class ResumeProject(
    val elementId: String,
    val name: String,
    val description: String? = null,
    val url: String? = null,
    val achievements: List<ResumeTextElement> = emptyList(),
    val skillElementIds: List<String> = emptyList(),
    val metadata: ResumeElementMetadata = ResumeElementMetadata(),
)

data class ResumeEducation(
    val elementId: String,
    val institution: String,
    val degree: String? = null,
    val fieldOfStudy: String? = null,
    val startDate: ResumeDate? = null,
    val endDate: ResumeDate? = null,
    val description: String? = null,
    val metadata: ResumeElementMetadata = ResumeElementMetadata(),
)

data class ResumeCertification(
    val elementId: String,
    val name: String,
    val issuer: String? = null,
    val issuedAt: ResumeDate? = null,
    val expiresAt: ResumeDate? = null,
    val credentialUrl: String? = null,
    val metadata: ResumeElementMetadata = ResumeElementMetadata(),
)

data class ResumeLanguage(
    val elementId: String,
    val name: String,
    val proficiency: String? = null,
    val metadata: ResumeElementMetadata = ResumeElementMetadata(),
)

data class ResumeSkill(
    val elementId: String,
    val name: String,
    val category: String? = null,
    val metadata: ResumeElementMetadata = ResumeElementMetadata(),
)

data class ResumeDate(val year: Int, val month: Int? = null)

data class ResumeElementMetadata(
    val provenance: ResumeProvenance? = null,
    val confidence: Double? = null,
    val reviewStatus: ResumeReviewStatus = ResumeReviewStatus.UNREVIEWED,
)

enum class ResumeReviewStatus { UNREVIEWED, CONFIRMED, REJECTED }

data class ResumeProvenance(
    val sourceText: String,
    val pageNumber: Int? = null,
    val boundingBox: ResumeBoundingBox? = null,
    val coordinateSpace: ResumeCoordinateSpace? = null,
)

data class ResumeBoundingBox(
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double,
)

data class ResumeElementRef(
    val section: String,
    val elementId: String,
    val text: String,
    val metadata: ResumeElementMetadata,
    val element: Any,
)

fun StructuredResume.elementRefs(): List<ResumeElementRef> = buildList {
    identity?.let {
        add(
            ResumeElementRef(
                "IDENTITY", it.elementId, listOfNotNull(it.fullName, it.headline).joinToString(" | "), it.metadata, it
            )
        )
    }
    summary?.let { add(ResumeElementRef("SUMMARY", it.elementId, it.text, it.metadata, it)) }
    contacts.forEach { add(ResumeElementRef("CONTACT", it.elementId, "${it.type}: ${it.value}", it.metadata, it)) }
    experiences.forEach { experience ->
        add(
            ResumeElementRef(
                "EXPERIENCE", experience.elementId, "${experience.company} | ${experience.role}",
                experience.metadata, experience,
            )
        )
        experience.achievements.forEach {
            add(ResumeElementRef("EXPERIENCE_ACHIEVEMENT", it.elementId, it.text, it.metadata, it))
        }
    }
    projects.forEach { project ->
        add(ResumeElementRef("PROJECT", project.elementId, project.name, project.metadata, project))
        project.achievements.forEach {
            add(ResumeElementRef("PROJECT_ACHIEVEMENT", it.elementId, it.text, it.metadata, it))
        }
    }
    education.forEach {
        add(
            ResumeElementRef(
                "EDUCATION", it.elementId, listOfNotNull(it.institution, it.degree).joinToString(" | "), it.metadata, it
            )
        )
    }
    certifications.forEach { add(ResumeElementRef("CERTIFICATION", it.elementId, it.name, it.metadata, it)) }
    languages.forEach {
        add(
            ResumeElementRef(
                "LANGUAGE", it.elementId, listOfNotNull(it.name, it.proficiency).joinToString(" | "), it.metadata, it
            )
        )
    }
    skills.forEach { add(ResumeElementRef("SKILL", it.elementId, it.name, it.metadata, it)) }
}

fun StructuredResume.confirmedOnly(): StructuredResume {
    val confirmedSkills = skills.filter { it.metadata.isConfirmed() }
    val confirmedSkillIds = confirmedSkills.map { it.elementId }.toSet()
    return StructuredResume(
        schemaVersion = schemaVersion,
        identity = identity?.takeIf { it.metadata.isConfirmed() },
        summary = summary?.takeIf { it.metadata.isConfirmed() },
        contacts = contacts.filter { it.metadata.isConfirmed() },
        experiences = experiences.filter { it.metadata.isConfirmed() }.map { experience ->
            experience.copy(achievements = experience.achievements.filter { it.metadata.isConfirmed() })
        },
        projects = projects.filter { it.metadata.isConfirmed() }.map { project ->
            project.copy(
                achievements = project.achievements.filter { it.metadata.isConfirmed() },
                skillElementIds = project.skillElementIds.filter { it in confirmedSkillIds },
            )
        },
        education = education.filter { it.metadata.isConfirmed() },
        certifications = certifications.filter { it.metadata.isConfirmed() },
        languages = languages.filter { it.metadata.isConfirmed() },
        skills = confirmedSkills,
    )
}

private fun ResumeElementMetadata.isConfirmed() = reviewStatus == ResumeReviewStatus.CONFIRMED

class StructuredResumeValidator {
    fun validate(resume: StructuredResume, requireReviewed: Boolean = false) {
        require(resume.schemaVersion == StructuredResume.CURRENT_SCHEMA_VERSION) {
            "Unsupported structured resume schema version: ${resume.schemaVersion}"
        }

        val elements = resume.elements()
        require(elements.all { it.elementId.isNotBlank() }) { "Resume elementId must not be blank" }
        require(elements.map { it.elementId }.distinct().size == elements.size) {
            "Resume elementId values must be unique"
        }
        resume.identity?.let { require(it.fullName.isNotBlank()) { "Resume identity fullName must not be blank" } }
        resume.summary?.let { require(it.text.isNotBlank()) { "Resume summary must not be blank" } }
        require(resume.contacts.all { it.value.isNotBlank() }) { "Resume contact value must not be blank" }
        require(resume.experiences.all { it.company.isNotBlank() && it.role.isNotBlank() }) {
            "Resume experience company and role must not be blank"
        }
        require(resume.projects.all { it.name.isNotBlank() }) { "Resume project name must not be blank" }
        require(resume.education.all { it.institution.isNotBlank() }) {
            "Resume education institution must not be blank"
        }
        require(resume.certifications.all { it.name.isNotBlank() }) {
            "Resume certification name must not be blank"
        }
        require(resume.languages.all { it.name.isNotBlank() }) { "Resume language name must not be blank" }
        require(resume.skills.all { it.name.isNotBlank() }) { "Resume skill name must not be blank" }
        require(
            resume.experiences.flatMap { it.achievements }.all { it.text.isNotBlank() } &&
                resume.projects.flatMap { it.achievements }.all { it.text.isNotBlank() }
        ) { "Resume achievement text must not be blank" }
        elements.forEach { element ->
            element.metadata.confidence?.let {
                require(it in 0.0..1.0) { "Resume element confidence must be between 0 and 1" }
            }
            element.metadata.provenance?.let { provenance ->
                require(provenance.sourceText.isNotBlank()) { "Resume provenance sourceText must not be blank" }
                require(provenance.pageNumber == null || provenance.pageNumber > 0) {
                    "Resume provenance pageNumber must be positive"
                }
                provenance.boundingBox?.let { box ->
                    require(box.width >= 0 && box.height >= 0) { "Resume bounding box size must not be negative" }
                }
            }
            if (requireReviewed) {
                require(element.metadata.reviewStatus != ResumeReviewStatus.UNREVIEWED) {
                    "All resume elements must be reviewed before confirmation"
                }
            }
        }

        resume.allDates().forEach { date ->
            require(date.year in 1900..2200) { "Resume date year must be between 1900 and 2200" }
            require(date.month == null || date.month in 1..12) { "Resume date month must be between 1 and 12" }
        }
        resume.experiences.forEach { experience ->
            require(!experience.current || experience.endDate == null) {
                "Current resume experience must not have an end date"
            }
            require(chronological(experience.startDate, experience.endDate)) {
                "Resume experience start date must not be after end date"
            }
        }
        resume.education.forEach { entry ->
            require(chronological(entry.startDate, entry.endDate)) {
                "Resume education start date must not be after end date"
            }
        }
        resume.certifications.forEach { certification ->
            require(chronological(certification.issuedAt, certification.expiresAt)) {
                "Resume certification issue date must not be after expiry date"
            }
        }
        val skillIds = resume.skills.map { it.elementId }.toSet()
        require(resume.projects.flatMap { it.skillElementIds }.all { it in skillIds }) {
            "Project skillElementIds must reference skills from the same resume"
        }
    }

    private data class Element(val elementId: String, val metadata: ResumeElementMetadata)

    private fun StructuredResume.elements(): List<Element> = buildList {
        identity?.let { add(Element(it.elementId, it.metadata)) }
        summary?.let { add(Element(it.elementId, it.metadata)) }
        contacts.forEach { add(Element(it.elementId, it.metadata)) }
        experiences.forEach { experience ->
            add(Element(experience.elementId, experience.metadata))
            experience.achievements.forEach { add(Element(it.elementId, it.metadata)) }
        }
        projects.forEach { project ->
            add(Element(project.elementId, project.metadata))
            project.achievements.forEach { add(Element(it.elementId, it.metadata)) }
        }
        education.forEach { add(Element(it.elementId, it.metadata)) }
        certifications.forEach { add(Element(it.elementId, it.metadata)) }
        languages.forEach { add(Element(it.elementId, it.metadata)) }
        skills.forEach { add(Element(it.elementId, it.metadata)) }
    }

    private fun StructuredResume.allDates(): List<ResumeDate> = buildList {
        experiences.forEach { experience ->
            experience.startDate?.let(::add)
            experience.endDate?.let(::add)
        }
        education.forEach { entry ->
            entry.startDate?.let(::add)
            entry.endDate?.let(::add)
        }
        certifications.forEach { certification ->
            certification.issuedAt?.let(::add)
            certification.expiresAt?.let(::add)
        }
    }

    private fun chronological(start: ResumeDate?, end: ResumeDate?): Boolean {
        if (start == null || end == null) return true
        val startValue = start.year * 12 + (start.month ?: 1)
        val endValue = end.year * 12 + (end.month ?: 12)
        return startValue <= endValue
    }
}
