package th.sibraine.jobagent.tailoring.domain

import th.sibraine.jobagent.candidate.domain.StructuredResume
import th.sibraine.jobagent.vacancy.domain.Vacancy
import java.time.Instant

data class CoverLetter(
    val text: String,
    val generatedAt: Instant,
)

data class CoverLetterRequest(
    val vacancy: Vacancy,
    val resume: StructuredResume,
)

fun interface CoverLetterGenerator {
    fun generate(request: CoverLetterRequest): String
}
