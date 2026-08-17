package th.sibraine.jobagent.candidate.domain

import java.util.UUID

data class CandidateProfile(
    val id: UUID,
    val generalInfo: GeneralInfo,
    val roles: List<String> = emptyList(),
    val projects: List<Project> = emptyList(),
    val skills: Set<String> = emptySet(),
    val preferences: JobPreferences = JobPreferences(),
    val constraints: List<String> = emptyList(),
    val facts: List<CandidateFact> = emptyList(),
    val label: String? = null,
    val contacts: List<ResumeContact> = emptyList(),
    val experiences: List<ResumeExperience> = emptyList(),
)

data class GeneralInfo(val displayName: String, val headline: String? = null)

data class Project(val name: String, val description: String, val factIds: List<String> = emptyList())

data class JobPreferences(
    val targetRoles: List<String> = emptyList(),
    val locations: List<String> = emptyList(),
    val remoteOnly: Boolean = false,
)

data class CandidateFact(
    val factId: String,
    val type: FactType,
    val text: String,
    val verified: Boolean,
)

enum class FactType { EXPERIENCE, SKILL, PROJECT, EDUCATION, CERTIFICATION, OTHER }
