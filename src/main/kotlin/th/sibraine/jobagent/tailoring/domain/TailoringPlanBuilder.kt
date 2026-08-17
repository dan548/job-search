package th.sibraine.jobagent.tailoring.domain

import th.sibraine.jobagent.candidate.domain.CandidateProfile
import th.sibraine.jobagent.candidate.domain.ResumeReviewStatus
import th.sibraine.jobagent.candidate.domain.StructuredResume
import th.sibraine.jobagent.candidate.domain.elementRefs
import th.sibraine.jobagent.matching.domain.MatchResult
import th.sibraine.jobagent.matching.domain.RequirementImportance
import th.sibraine.jobagent.matching.domain.RequirementStatus
import java.security.MessageDigest

class TailoringPlanBuilder {
    fun build(
        plan: TailoringPlan,
        base: StructuredResume,
        confirmed: StructuredResume,
        tailored: StructuredResume,
        profile: CandidateProfile,
        match: MatchResult,
    ): TailoringPlan {
        val evidence = EvidenceIndex(confirmed, profile)
        val gaps = gaps(match)
        return plan.copy(
            summary = plan.summary?.resolve(evidence),
            experiences = plan.experiences.map { it.copy(achievements = it.achievements.map { a -> a.resolve(evidence) }) },
            projects = plan.projects.map { it.copy(achievements = it.achievements.map { a -> a.resolve(evidence) }) },
            omissions = omissions(base, tailored),
            gaps = gaps,
            questions = questions(gaps),
        )
    }

    private fun TailoredText.resolve(index: EvidenceIndex) = copy(evidence = index.resolve(evidence))

    private fun omissions(base: StructuredResume, tailored: StructuredResume): List<TailoringOmission> {
        val kept = tailored.elementRefs().map { it.elementId }.toSet()
        return base.elementRefs()
            .filter { it.elementId !in kept }
            .map { ref ->
                TailoringOmission(
                    section = ref.section,
                    elementId = ref.elementId,
                    text = ref.text,
                    reason = if (ref.metadata.reviewStatus == ResumeReviewStatus.CONFIRMED) {
                        OmissionReason.NOT_SELECTED
                    } else {
                        OmissionReason.NOT_CONFIRMED
                    },
                )
            }
    }

    private fun gaps(match: MatchResult): List<TailoringGap> = match.requirementEvidenceMatrix
        .filter { it.status != RequirementStatus.MATCHED }
        .map { TailoringGap(it.requirement, it.importance, it.status) }

    private fun questions(gaps: List<TailoringGap>): List<TailoringQuestion> = gaps
        .filter { it.importance == RequirementImportance.HARD_REQUIREMENT || it.status == RequirementStatus.BLOCKED }
        .map { gap ->
            TailoringQuestion(
                questionId = questionId(gap.requirement),
                question = when (gap.status) {
                    RequirementStatus.BLOCKED ->
                        "The vacancy blocks you on '${gap.requirement}'. How do you want to address it before applying?"
                    else ->
                        "No confirmed evidence covers '${gap.requirement}'. Can you supply a verifiable fact for it?"
                },
                requirement = gap.requirement,
                importance = gap.importance,
            )
        }

    private fun questionId(requirement: String): String {
        val hash = MessageDigest.getInstance("SHA-256").digest(requirement.lowercase().trim().toByteArray())
            .take(10).joinToString("") { "%02x".format(it) }
        return "question-$hash"
    }
}
