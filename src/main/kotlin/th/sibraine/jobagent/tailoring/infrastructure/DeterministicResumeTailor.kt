package th.sibraine.jobagent.tailoring.infrastructure

import th.sibraine.jobagent.candidate.domain.ResumeTextElement
import th.sibraine.jobagent.matching.domain.VacancyAnalysis
import th.sibraine.jobagent.tailoring.domain.*
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

@Component
@ConditionalOnProperty(
    prefix = "job-agent.ai.openai",
    name = ["enabled"],
    havingValue = "false",
    matchIfMissing = true,
)
class DeterministicResumeTailor : ResumeTailor {
    override fun tailor(request: TailoringRequest): TailoringPlan {
        val keywords = keywords(request.analysis)
        val resume = request.resume
        return TailoringPlan(
            summary = resume.summary?.let { reuse(it, keywords) },
            experiences = resume.experiences.map { experience ->
                TailoredExperience(experience.elementId, select(experience.achievements, keywords))
            },
            projects = resume.projects
                .map { project ->
                    project to score(
                        listOfNotNull(project.name, project.description) + project.achievements.map { it.text },
                        keywords,
                    )
                }
                .filter { (_, score) -> score > 0 }
                .sortedByDescending { (_, score) -> score }
                .map { (project, _) -> TailoredProject(project.elementId, select(project.achievements, keywords)) },
            skillElementIds = resume.skills
                .sortedByDescending { score(listOf(it.name), keywords) }
                .map { it.elementId },
            rationale = "Reordered confirmed resume content by vacancy keyword coverage without adding new claims.",
        )
    }

    private fun select(achievements: List<ResumeTextElement>, keywords: List<Keyword>): List<TailoredText> {
        val scored = achievements.map { it to score(listOf(it.text), keywords) }
        val relevant = scored.filter { (_, score) -> score > 0 }
        return (if (relevant.size >= MIN_SELECTED_ACHIEVEMENTS) relevant else scored)
            .sortedByDescending { (_, score) -> score }
            .map { (achievement, _) -> reuse(achievement, keywords) }
    }

    private fun reuse(element: ResumeTextElement, keywords: List<Keyword>) = TailoredText(
        text = element.text,
        evidence = listOf(EvidenceRef(EvidenceKind.RESUME_ELEMENT, element.elementId)),
        sourceElementId = element.elementId,
        addressedRequirements = keywords.filter { matches(element.text, it) }.map { it.value },
    )

    private fun score(texts: List<String>, keywords: List<Keyword>): Int = keywords
        .filter { keyword -> texts.any { matches(it, keyword) } }
        .sumOf { it.weight }

    private fun matches(text: String, keyword: Keyword): Boolean = normalize(text).contains(normalize(keyword.value))

    private fun keywords(analysis: VacancyAnalysis): List<Keyword> = buildList {
        analysis.requiredSkills.forEach { add(Keyword(it, REQUIRED_WEIGHT)) }
        analysis.preferredSkills.forEach { add(Keyword(it, PREFERRED_WEIGHT)) }
        analysis.niceToHave.forEach { add(Keyword(it, PREFERRED_WEIGHT)) }
    }.filter { it.value.isNotBlank() }.distinctBy { normalize(it.value) }

    private fun normalize(value: String): String = value.lowercase()
        .replace("springboot", "spring boot")
        .replace("restful", "rest")
        .replace(Regex("[^a-zа-я0-9+#]+"), " ")
        .trim()

    private data class Keyword(val value: String, val weight: Int)

    private companion object {
        const val REQUIRED_WEIGHT = 2
        const val PREFERRED_WEIGHT = 1
        const val MIN_SELECTED_ACHIEVEMENTS = 2
    }
}
