package th.sibraine.jobagent.matching.infrastructure

import th.sibraine.jobagent.matching.domain.SalaryRange
import th.sibraine.jobagent.matching.domain.VacancyAnalysis
import th.sibraine.jobagent.matching.domain.VacancyAnalyzer
import th.sibraine.jobagent.vacancy.domain.Vacancy
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

@Component
@ConditionalOnProperty(
    prefix = "job-agent.ai.openai",
    name = ["enabled"],
    havingValue = "false",
    matchIfMissing = true,
)
class FakeVacancyAnalyzer : VacancyAnalyzer {
    private val technologies = listOf(
        "Java", "Kotlin", "Spring Boot", "Ktor", "PostgreSQL", "Kafka",
        "Docker", "Kubernetes", "Testcontainers", "AWS", "REST API",
        "Redis", "Python", "Go", "React", "TypeScript",
    )

    override fun analyze(vacancy: Vacancy): VacancyAnalysis {
        val text = "${vacancy.title}\n${vacancy.description}"
        val found = technologies.filter { text.contains(it, ignoreCase = true) }
        val preferredSection = text.substringAfterAny(listOf("nice to have", "preferred", "будет плюсом"))
        val preferred = found.filter { preferredSection?.contains(it, ignoreCase = true) == true }
        val required = found - preferred.toSet()
        val seniority = listOf("Lead", "Senior", "Middle", "Junior").firstOrNull {
            vacancy.title.contains(it, ignoreCase = true)
        }
        val authorization = listOf("work authorization", "visa sponsorship", "право на работу")
            .filter { text.contains(it, ignoreCase = true) }
        val languages = listOf("English", "Russian", "Английский", "Русский")
            .filter { text.contains(it, ignoreCase = true) }

        return VacancyAnalysis(
            role = vacancy.title,
            seniority = seniority,
            requiredSkills = required,
            preferredSkills = preferred,
            hardRequirements = authorization,
            niceToHave = preferred,
            languageRequirements = languages,
            locationConstraints = vacancy.location?.let(::listOf).orEmpty(),
            workAuthorizationConstraints = authorization,
            salary = if (vacancy.salaryFrom != null || vacancy.salaryTo != null) {
                SalaryRange(vacancy.salaryFrom?.toPlainString(), vacancy.salaryTo?.toPlainString(), vacancy.salaryCurrency)
            } else null,
        )
    }

    private fun String.substringAfterAny(markers: List<String>): String? {
        val lower = lowercase()
        val index = markers.map { lower.indexOf(it) }.filter { it >= 0 }.minOrNull() ?: return null
        return substring(index)
    }
}
