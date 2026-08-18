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
    private data class QuestionTheme(
        val key: String,
        val title: String,
        val kind: TailoringQuestionKind = TailoringQuestionKind.EVIDENCE,
    )

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
        val gapGroups = gapGroups(gaps)
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
        .map { TailoringGap(it.requirement, it.importance, it.status) }

    private fun gapGroups(gaps: List<TailoringGap>): List<TailoringGapGroup> = gaps
        .groupBy { theme(it.requirement) }
        .map { (theme, relatedGaps) ->
            TailoringGapGroup(
                groupId = theme.key,
                title = theme.title,
                importance = relatedGaps.minBy { importanceRank(it.importance) }.importance,
                status = relatedGaps.minBy { statusRank(it.status) }.status,
                kind = theme.kind,
                requirements = relatedGaps.map { it.requirement }.distinct(),
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

    private fun theme(requirement: String): QuestionTheme {
        val value = requirement.lowercase()
        return when {
            value.containsAny("remote", "contractor", "onsite", "on-site", "hybrid") ->
                QuestionTheme("work-format", "Формат работы", TailoringQuestionKind.PREFERENCE)
            value.containsAny("tbilisi", "belgrade", "lisbon", "madrid", "riga", "tallinn", "valencia", "yerevan", "location", "relocat") ->
                QuestionTheme("location", "Локация и переезд", TailoringQuestionKind.PREFERENCE)
            value.containsAny("sponsor", "visa", "work authorization", "right to work") ->
                QuestionTheme("work-authorization", "Разрешение на работу", TailoringQuestionKind.PREFERENCE)
            value.containsAny("kotlin", "java", "jvm", "gradle", "spring", "dagger", "junit", "apache commons") ->
                QuestionTheme("jvm", "Java, Kotlin и экосистема JVM")
            value.containsAny("python", "blender", "3d", "2d", "opengl", "webgl", "raytrac", "computer vision", "geometry") ->
                QuestionTheme("python-3d", "Python, Blender и 2D/3D-технологии")
            value.containsAny("solid", "clean architecture", "maintainable", "object-oriented", "automated test", "reliable code", "design pattern") ->
                QuestionTheme("code-quality", "Архитектура, качество кода и тестирование")
            value.containsAny("linux", "docker", "kubernetes") ->
                QuestionTheme("infrastructure", "Linux и инфраструктура")
            value.containsAny("sql", "sqlite", "mysql", "postgres") ->
                QuestionTheme("databases", "SQL и базы данных")
            value.containsAny("english", "англий") -> QuestionTheme("english", "Английский язык")
            value.containsAny("math", "algorithm", "data-heavy") ->
                QuestionTheme("algorithms", "Математика и алгоритмические задачи")
            else -> QuestionTheme("requirement:${normalize(requirement)}", requirement)
        }
    }

    private fun String.containsAny(vararg terms: String) = terms.any(::contains)

    private fun normalize(value: String) = value.lowercase().trim().replace(Regex("[^\\p{L}\\p{N}]+"), "-")

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
