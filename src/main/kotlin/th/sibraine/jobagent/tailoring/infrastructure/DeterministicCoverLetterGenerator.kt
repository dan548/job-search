package th.sibraine.jobagent.tailoring.infrastructure

import th.sibraine.jobagent.tailoring.domain.CoverLetterGenerator
import th.sibraine.jobagent.tailoring.domain.CoverLetterRequest
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

@Component
@ConditionalOnProperty(
    prefix = "job-agent.ai.openai",
    name = ["enabled"],
    havingValue = "false",
    matchIfMissing = true,
)
class DeterministicCoverLetterGenerator : CoverLetterGenerator {
    override fun generate(request: CoverLetterRequest): String {
        val resume = request.resume
        val vacancy = request.vacancy
        val name = resume.identity?.fullName
        val summary = resume.summary?.text
        val achievement = resume.experiences.asSequence().flatMap { it.achievements.asSequence() }.firstOrNull()?.text
        val skills = resume.skills.take(MAX_SKILLS).joinToString(", ") { it.name }
        val russian = CYRILLIC.containsMatchIn(vacancy.title + " " + vacancy.description)

        return if (russian) {
            buildList {
                add("Здравствуйте!")
                add("Хочу откликнуться на позицию «${vacancy.title}» в ${vacancy.company}.")
                summary?.let { add(it.trim().sentence()) }
                achievement?.let { add("Один из релевантных примеров моего опыта: ${it.trim().sentence(lowercaseFirst = true)}") }
                if (skills.isNotBlank()) add("В резюме отражён мой опыт с технологиями и навыками: $skills.")
                add("Буду рад обсудить, как мой опыт может быть полезен вашей команде.")
                name?.let { add("С уважением,\n$it") }
            }.joinToString("\n\n")
        } else {
            buildList {
                add("Hello,")
                add("I am writing to apply for the ${vacancy.title} position at ${vacancy.company}.")
                summary?.let { add(it.trim().sentence()) }
                achievement?.let { add("One relevant example from my experience: ${it.trim().sentence(lowercaseFirst = true)}") }
                if (skills.isNotBlank()) add("My resume reflects experience with the following relevant skills and technologies: $skills.")
                add("I would welcome the opportunity to discuss how my experience could contribute to your team.")
                name?.let { add("Kind regards,\n$it") }
            }.joinToString("\n\n")
        }
    }

    private fun String.sentence(lowercaseFirst: Boolean = false): String {
        val value = if (lowercaseFirst && isNotEmpty()) first().lowercase() + drop(1) else this
        return if (value.endsWith('.') || value.endsWith('!') || value.endsWith('?')) value else "$value."
    }

    private companion object {
        const val MAX_SKILLS = 5
        val CYRILLIC = Regex("[А-Яа-яЁё]")
    }
}
