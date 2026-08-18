package th.sibraine.jobagent.tailoring.domain

import th.sibraine.jobagent.candidate.domain.CandidateProfile
import th.sibraine.jobagent.candidate.domain.ResumeReviewStatus
import th.sibraine.jobagent.candidate.domain.StructuredResume
import th.sibraine.jobagent.candidate.domain.elementRefs
import th.sibraine.jobagent.matching.domain.MatchResult
import th.sibraine.jobagent.matching.domain.RequirementImportance
import th.sibraine.jobagent.matching.domain.RequirementStatus
import th.sibraine.jobagent.matching.domain.RequirementThemeClassifier
import java.security.MessageDigest

class TailoringPlanBuilder {
    private val themes = RequirementThemeClassifier()

    fun build(
        plan: TailoringPlan,
        base: StructuredResume,
        confirmed: StructuredResume,
        tailored: StructuredResume,
        profile: CandidateProfile,
        match: MatchResult,
        decisions: Map<String, TailoringGapDecision> = emptyMap(),
    ): TailoringPlan {
        val evidence = EvidenceIndex(confirmed, profile)
        val gaps = gaps(match)
        val gapGroups = gapGroups(gaps).map { it.copy(decision = decisions[it.groupId]) }
        return plan.copy(
            summary = plan.summary?.resolve(evidence),
            experiences = plan.experiences.map { it.copy(achievements = it.achievements.map { a -> a.resolve(evidence) }) },
            projects = plan.projects.map { it.copy(achievements = it.achievements.map { a -> a.resolve(evidence) }) },
            omissions = omissions(base, tailored),
            gaps = gaps,
            gapGroups = gapGroups,
            questions = questions(gapGroups),
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
        .map { TailoringGap(it.requirement, it.importance, it.status, it.relatedRequirements) }

    private fun gapGroups(gaps: List<TailoringGap>): List<TailoringGapGroup> = gaps
        .groupBy { themes.classify(it.requirement) }
        .map { (theme, relatedGaps) ->
            TailoringGapGroup(
                groupId = theme.key,
                title = theme.title,
                importance = relatedGaps.minBy { importanceRank(it.importance) }.importance,
                status = relatedGaps.minBy { statusRank(it.status) }.status,
                kind = if (theme.preference) TailoringQuestionKind.PREFERENCE else TailoringQuestionKind.EVIDENCE,
                requirements = relatedGaps.flatMap { it.relatedRequirements.ifEmpty { listOf(it.requirement) } }.distinct(),
            )
        }
        .sortedWith(
            compareBy<TailoringGapGroup> { statusRank(it.status) }
                .thenBy { importanceRank(it.importance) }
                .thenBy { it.title.lowercase() },
        )

    private fun questions(gapGroups: List<TailoringGapGroup>): List<TailoringQuestion> {
        val grouped = gapGroups
            .filter { it.importance == RequirementImportance.HARD_REQUIREMENT || it.status == RequirementStatus.BLOCKED }
            .map { group ->
                TailoringQuestion(
                    questionId = questionId(group.groupId),
                    groupId = group.groupId,
                    question = when {
                        group.kind == TailoringQuestionKind.PREFERENCE ->
                            "Проверьте настройки отклика: подходит ли вам это условие вакансии?"
                        group.status == RequirementStatus.BLOCKED ->
                            "Это условие может блокировать отклик. Есть ли подтверждаемый факт, который меняет оценку?"
                        else ->
                            "Есть ли у вас конкретный подтверждаемый опыт по этой теме?"
                    },
                    requirement = group.title,
                    importance = group.importance,
                    kind = group.kind,
                    relatedRequirements = group.requirements,
                    decision = group.decision,
                )
            }

        val preferences = grouped
            .filter { it.kind == TailoringQuestionKind.PREFERENCE }
            .sortedBy { it.requirement.lowercase() }
            .take(MAX_PREFERENCE_QUESTIONS)
        val evidence = grouped
            .filter { it.kind == TailoringQuestionKind.EVIDENCE }
            .sortedWith(compareBy<TailoringQuestion> { importanceRank(it.importance) }.thenBy { it.requirement.lowercase() })
            .take(MAX_PRIORITY_QUESTIONS - preferences.size)
        return evidence + preferences
    }

    private fun importanceRank(importance: RequirementImportance) = when (importance) {
        RequirementImportance.HARD_REQUIREMENT -> 0
        RequirementImportance.SOFT_REQUIREMENT -> 1
        RequirementImportance.NICE_TO_HAVE -> 2
    }

    private fun statusRank(status: RequirementStatus) = when (status) {
        RequirementStatus.BLOCKED -> 0
        RequirementStatus.MISSING -> 1
        RequirementStatus.UNASSESSED -> 2
        RequirementStatus.MATCHED -> 3
    }

    private fun questionId(requirement: String): String {
        val hash = MessageDigest.getInstance("SHA-256").digest(requirement.lowercase().trim().toByteArray())
            .take(10).joinToString("") { "%02x".format(it) }
        return "question-$hash"
    }

    private companion object {
        const val MAX_PRIORITY_QUESTIONS = 8
        const val MAX_PREFERENCE_QUESTIONS = 2
    }
}
