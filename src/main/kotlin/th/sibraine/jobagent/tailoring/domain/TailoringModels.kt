package th.sibraine.jobagent.tailoring.domain

import th.sibraine.jobagent.candidate.domain.CandidateProfile
import th.sibraine.jobagent.candidate.domain.ResumeDiffChange
import th.sibraine.jobagent.candidate.domain.StructuredResume
import th.sibraine.jobagent.matching.domain.MatchResult
import th.sibraine.jobagent.matching.domain.RequirementImportance
import th.sibraine.jobagent.matching.domain.RequirementStatus
import th.sibraine.jobagent.matching.domain.VacancyAnalysis
import th.sibraine.jobagent.vacancy.domain.Vacancy
import java.time.Instant
import java.util.UUID

const val CURRENT_TEMPLATE_ID = "ats-single-column"
const val CURRENT_TEMPLATE_VERSION = 1

enum class EvidenceKind { RESUME_ELEMENT, PROFILE_FACT }

data class EvidenceRef(
    val kind: EvidenceKind,
    val id: String,
    val text: String? = null,
)

data class TailoredText(
    val text: String,
    val evidence: List<EvidenceRef> = emptyList(),
    val sourceElementId: String? = null,
    val addressedRequirements: List<String> = emptyList(),
)

data class TailoredExperience(
    val sourceElementId: String,
    val achievements: List<TailoredText> = emptyList(),
)

data class TailoredProject(
    val sourceElementId: String,
    val achievements: List<TailoredText> = emptyList(),
)

data class TailoringPlan(
    val summary: TailoredText? = null,
    val experiences: List<TailoredExperience> = emptyList(),
    val projects: List<TailoredProject> = emptyList(),
    val skillElementIds: List<String> = emptyList(),
    val rationale: String = "",
    val omissions: List<TailoringOmission> = emptyList(),
    val gaps: List<TailoringGap> = emptyList(),
    val questions: List<TailoringQuestion> = emptyList(),
)

data class TailoringOmission(
    val section: String,
    val elementId: String,
    val text: String,
    val reason: OmissionReason,
)

enum class OmissionReason { NOT_CONFIRMED, NOT_SELECTED }

data class TailoringGap(
    val requirement: String,
    val importance: RequirementImportance,
    val status: RequirementStatus,
)

enum class TailoringQuestionKind { EVIDENCE, PREFERENCE }

data class TailoringQuestion(
    val questionId: String,
    val question: String,
    val requirement: String,
    val importance: RequirementImportance,
    val kind: TailoringQuestionKind = TailoringQuestionKind.EVIDENCE,
    val relatedRequirements: List<String> = emptyList(),
)

data class TailoringRequest(
    val vacancy: Vacancy,
    val analysis: VacancyAnalysis,
    val match: MatchResult,
    val profile: CandidateProfile,
    val resume: StructuredResume,
)

interface ResumeTailor {
    fun tailor(request: TailoringRequest): TailoringPlan
}

data class ResumeVariant(
    val variantId: UUID,
    val version: Long,
    val vacancyId: UUID,
    val candidateProfileId: UUID,
    val baseImportId: UUID?,
    val baseImportVersion: Long?,
    val templateId: String,
    val templateVersion: Int,
    val plan: TailoringPlan,
    val resume: StructuredResume,
    val diff: List<ResumeDiffChange>,
    val createdAt: Instant,
    val reviewedAt: Instant? = null,
    val coverLetter: CoverLetter? = null,
)
