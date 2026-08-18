package th.sibraine.jobagent.candidate.application

import th.sibraine.jobagent.candidate.domain.*
import th.sibraine.jobagent.candidate.infrastructure.CandidateProfileEntity
import th.sibraine.jobagent.candidate.infrastructure.CandidateProfileJpaRepository
import th.sibraine.jobagent.shared.NotFoundException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.util.Locale
import java.util.UUID

const val SINGLE_USER_PROFILE_ID = "00000000-0000-0000-0000-000000000001"

data class CandidateIdentitySummary(
    val id: UUID,
    val label: String,
    val displayName: String,
    val headline: String?,
    val active: Boolean,
)

@Service
class CandidateProfileService(
    private val repository: CandidateProfileJpaRepository,
    private val clock: Clock,
) {
    val profileId: UUID
        get() = repository.findFirstByActiveTrue()?.id ?: UUID.fromString(SINGLE_USER_PROFILE_ID)

    @Transactional
    fun put(profile: CandidateProfile): CandidateProfile = put(profileId, profile)

    @Transactional
    fun put(id: UUID, profile: CandidateProfile): CandidateProfile {
        val normalized = withManualEvidence(profile.copy(id = id))
        require(normalized.facts.map { it.factId }.distinct().size == normalized.facts.size) { "factId values must be unique" }
        require(normalized.facts.all { it.factId.isNotBlank() }) { "factId must not be blank" }
        val now = Instant.now(clock)
        val entity = repository.findById(id).orElse(null)
        if (entity == null) {
            val active = repository.findFirstByActiveTrue() == null
            repository.save(CandidateProfileEntity(id, normalized, now, now, active))
        } else {
            entity.profile = normalized
            entity.updatedAt = now
        }
        return normalized
    }

    @Transactional(readOnly = true)
    fun get(): CandidateProfile = get(profileId)

    @Transactional(readOnly = true)
    fun get(id: UUID): CandidateProfile = repository.findById(id).orElseThrow {
        NotFoundException("CANDIDATE_PROFILE_NOT_FOUND", "Candidate profile not found")
    }.profile

    /** Adds a fact the user explicitly entered and confirmed in response to a vacancy gap. */
    @Transactional
    fun addConfirmedFact(type: FactType, text: String): CandidateProfile {
        val normalizedText = text.trim()
        require(normalizedText.isNotEmpty()) { "Confirmed fact must not be blank" }
        require(normalizedText.length <= 4_000) { "Confirmed fact must not exceed 4000 characters" }
        val current = get()
        val fact = CandidateFact(
            factId = manualFactId("fact", "${type.name}:$normalizedText"),
            type = type,
            text = normalizedText,
            verified = true,
        )
        return put(current.copy(facts = current.facts.filterNot { it.factId == fact.factId } + fact))
    }

    @Transactional(readOnly = true)
    fun identities(): List<CandidateIdentitySummary> = repository.findAllByOrderByCreatedAtAsc().map {
        CandidateIdentitySummary(
            it.id,
            it.profile.label?.takeIf(String::isNotBlank) ?: it.profile.generalInfo.displayName,
            it.profile.generalInfo.displayName,
            it.profile.generalInfo.headline,
            it.active,
        )
    }

    @Transactional
    fun createIdentity(label: String, displayName: String): CandidateProfile {
        val id = UUID.randomUUID()
        val profile = CandidateProfile(
            id = id,
            generalInfo = GeneralInfo(displayName.trim(), null),
            label = label.trim().ifBlank { displayName.trim() },
        )
        put(id, profile)
        activate(id)
        return profile
    }

    @Transactional
    fun activate(id: UUID): CandidateProfile {
        val target = repository.findById(id).orElseThrow {
            NotFoundException("CANDIDATE_PROFILE_NOT_FOUND", "Candidate profile not found")
        }
        repository.findFirstByActiveTrue()?.takeIf { it.id != id }?.let {
            it.active = false
            repository.saveAndFlush(it)
        }
        target.active = true
        return target.profile
    }

    @Transactional
    fun syncFromResume(profileId: UUID, resume: StructuredResume): CandidateProfile {
        val confirmed = resume.confirmedOnly()
        val existing = repository.findById(profileId).orElse(null)?.profile
        val importedFacts = confirmed.toCandidateFacts()
        val manualFacts = existing?.facts.orEmpty().filterNot {
            it.factId.startsWith(RESUME_FACT_PREFIX) || it.factId.startsWith("seed-fact-")
        }
        val manualContacts = existing?.contacts.orEmpty().filter { it.elementId.startsWith(MANUAL_PREFIX) }
        val manualExperiences = existing?.experiences.orEmpty().filter { it.elementId.startsWith(MANUAL_PREFIX) }
        val contacts = (confirmed.contacts + manualContacts).distinctBy { it.type to normalize(it.value) }
        val experiences = (confirmed.experiences + manualExperiences).distinctBy {
            listOf(normalize(it.company), normalize(it.role), it.startDate).joinToString("|")
        }
        val skills = buildSet {
            addAll(confirmed.skills.map { it.name.trim() }.filter(String::isNotEmpty))
            addAll(manualFacts.filter { it.type == FactType.SKILL && it.verified }.map { it.text.trim() })
            addAll(manualExperiences.flatMap(::experienceTechnologies))
        }
        val roles = (confirmed.experiences.map { it.role.trim() } + manualExperiences.map { it.role.trim() })
            .filter(String::isNotEmpty).distinct().ifEmpty { existing?.roles.orEmpty() }
        val projects = confirmed.projects.map { project ->
            Project(
                name = project.name,
                description = project.description.orEmpty(),
                factIds = buildList {
                    add(resumeFactId(project.elementId))
                    addAll(project.achievements.map { resumeFactId(it.elementId) })
                },
            )
        }
        return put(
            profileId,
            CandidateProfile(
                id = profileId,
                generalInfo = canonicalGeneralInfo(
                    identity = confirmed.identity,
                    existing = existing,
                ),
                roles = roles,
                projects = projects,
                skills = skills,
                preferences = existing?.preferences ?: JobPreferences(),
                constraints = existing?.constraints.orEmpty(),
                facts = manualFacts + importedFacts,
                label = existing?.label,
                contacts = contacts,
                experiences = experiences,
            )
        )
    }

    private fun canonicalGeneralInfo(identity: ResumeIdentity?, existing: CandidateProfile?): GeneralInfo = GeneralInfo(
        displayName = identity?.fullName ?: existing?.generalInfo?.displayName ?: "Candidate",
        headline = identity?.headline ?: existing?.generalInfo?.headline,
    )

    private fun withManualEvidence(profile: CandidateProfile): CandidateProfile {
        val facts = profile.facts.toMutableList()
        val manualExperienceFactIds = profile.experiences.filter { it.elementId.startsWith(MANUAL_PREFIX) }
            .map { manualFactId("experience", it.elementId) }.toSet()
        facts.removeIf { it.factId.startsWith("manual-experience-") && it.factId !in manualExperienceFactIds }
        val expandedSkills = profile.skills + profile.experiences.flatMap(::experienceTechnologies)
        expandedSkills.filter { skill -> facts.none { it.verified && it.type == FactType.SKILL && normalize(it.text) == normalize(skill) } }
            .forEach { skill -> facts += CandidateFact(manualFactId("skill", skill), FactType.SKILL, skill, true) }
        profile.experiences.filter { it.elementId.startsWith(MANUAL_PREFIX) }.forEach { experience ->
            val text = buildString {
                append("${experience.role} at ${experience.company}")
                experience.description?.takeIf(String::isNotBlank)?.let { append(": $it") }
            }
            val factId = manualFactId("experience", experience.elementId)
            facts.removeIf { it.factId == factId }
            facts += CandidateFact(factId, FactType.EXPERIENCE, text, true)
            experienceTechnologies(experience).forEach { technology ->
                if (facts.none { it.verified && it.type == FactType.SKILL && normalize(it.text) == normalize(technology) }) {
                    facts += CandidateFact(manualFactId("skill", technology), FactType.SKILL, technology, true)
                }
            }
        }
        return profile.copy(skills = expandedSkills, facts = facts.distinctBy { it.factId })
    }

    private fun experienceTechnologies(experience: ResumeExperience): List<String> =
        experience.technologies.ifEmpty {
            experience.metadata.provenance?.sourceText
            ?.substringAfter("Technologies:", "")
            ?.split(',')
            ?.map(String::trim)
            ?.filter(String::isNotEmpty)
            .orEmpty()
        }

    private fun StructuredResume.toCandidateFacts(): List<CandidateFact> = buildList {
        summary?.let { add(it.fact(FactType.OTHER, it.text)) }
        experiences.forEach { experience ->
            add(experience.fact(FactType.EXPERIENCE, "${experience.role} at ${experience.company}"))
            experience.description?.takeIf(String::isNotBlank)?.let {
                add(candidateFact("${experience.elementId}:description", FactType.EXPERIENCE, it))
            }
            experience.achievements.forEach { add(it.fact(FactType.EXPERIENCE, it.text)) }
        }
        projects.forEach { project ->
            add(project.fact(FactType.PROJECT, listOfNotNull(project.name, project.description).joinToString(": ")))
            project.achievements.forEach { add(it.fact(FactType.PROJECT, it.text)) }
        }
        education.forEach { add(it.fact(FactType.EDUCATION, listOfNotNull(it.degree, it.fieldOfStudy, it.institution).joinToString(", "))) }
        certifications.forEach { add(it.fact(FactType.CERTIFICATION, listOfNotNull(it.name, it.issuer).joinToString(", "))) }
        languages.forEach { add(it.fact(FactType.OTHER, listOfNotNull(it.name, it.proficiency).joinToString(": "))) }
        skills.forEach { add(it.fact(FactType.SKILL, it.name)) }
    }

    private fun ResumeTextElement.fact(type: FactType, text: String) = candidateFact(elementId, type, text)
    private fun ResumeExperience.fact(type: FactType, text: String) = candidateFact(elementId, type, text)
    private fun ResumeProject.fact(type: FactType, text: String) = candidateFact(elementId, type, text)
    private fun ResumeEducation.fact(type: FactType, text: String) = candidateFact(elementId, type, text)
    private fun ResumeCertification.fact(type: FactType, text: String) = candidateFact(elementId, type, text)
    private fun ResumeLanguage.fact(type: FactType, text: String) = candidateFact(elementId, type, text)
    private fun ResumeSkill.fact(type: FactType, text: String) = candidateFact(elementId, type, text)
    private fun candidateFact(elementId: String, type: FactType, text: String) =
        CandidateFact(resumeFactId(elementId), type, text, verified = true)
    private fun resumeFactId(elementId: String) = "$RESUME_FACT_PREFIX$elementId"

    private fun manualFactId(kind: String, value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(normalize(value).toByteArray())
            .take(8).joinToString("") { "%02x".format(it) }
        return "manual-$kind-$digest"
    }

    private fun normalize(value: String) = value.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")

    companion object {
        const val MANUAL_PREFIX = "manual-"
        private const val RESUME_FACT_PREFIX = "resume:"
    }
}
