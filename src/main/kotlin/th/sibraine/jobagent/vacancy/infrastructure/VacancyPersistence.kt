package th.sibraine.jobagent.vacancy.infrastructure

import th.sibraine.jobagent.vacancy.domain.*
import jakarta.persistence.*
import org.springframework.data.jpa.repository.JpaRepository
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "vacancies")
class VacancyEntity(
    @Id val id: UUID,
    @Enumerated(EnumType.STRING) val source: VacancySource,
    @Column(name = "external_id") val externalId: String?,
    val url: String?,
    val company: String,
    val title: String,
    @Column(columnDefinition = "text") val description: String,
    val location: String?,
    @Enumerated(EnumType.STRING) @Column(name = "employment_type") val employmentType: EmploymentType?,
    @Column(name = "salary_from") val salaryFrom: BigDecimal?,
    @Column(name = "salary_to") val salaryTo: BigDecimal?,
    @Column(name = "salary_currency", length = 3) val salaryCurrency: String?,
    @Column(name = "published_at") val publishedAt: Instant?,
    @Column(name = "created_at") val createdAt: Instant,
) {
    fun toDomain() = Vacancy(id, source, externalId, url, company, title, description, location, employmentType,
        salaryFrom, salaryTo, salaryCurrency, publishedAt, createdAt)

    companion object {
        fun from(v: Vacancy) = VacancyEntity(v.id, v.source, v.externalId, v.url, v.company, v.title,
            v.description, v.location, v.employmentType, v.salaryFrom, v.salaryTo, v.salaryCurrency,
            v.publishedAt, v.createdAt)
    }
}

interface VacancyJpaRepository : JpaRepository<VacancyEntity, UUID> {
    fun findAllByOrderByCreatedAtDesc(): List<VacancyEntity>
}
