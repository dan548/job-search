package th.sibraine.jobagent.vacancy.domain

import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

data class Vacancy(
    val id: UUID,
    val source: VacancySource,
    val externalId: String?,
    val url: String?,
    val company: String,
    val title: String,
    val description: String,
    val location: String?,
    val employmentType: EmploymentType?,
    val salaryFrom: BigDecimal?,
    val salaryTo: BigDecimal?,
    val salaryCurrency: String?,
    val publishedAt: Instant?,
    val createdAt: Instant,
)

enum class VacancySource { MANUAL, HH, GREENHOUSE, LEVER, ASHBY, OTHER }
enum class EmploymentType { FULL_TIME, PART_TIME, CONTRACT, INTERNSHIP, TEMPORARY }
