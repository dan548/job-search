package th.sibraine.jobagent.vacancy.application

import th.sibraine.jobagent.shared.NotFoundException
import th.sibraine.jobagent.vacancy.domain.Vacancy
import th.sibraine.jobagent.vacancy.infrastructure.VacancyEntity
import th.sibraine.jobagent.vacancy.infrastructure.VacancyJpaRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.util.UUID

@Service
class VacancyService(private val repository: VacancyJpaRepository, private val clock: Clock) {
    @Transactional
    fun create(vacancy: Vacancy): Vacancy {
        val normalized = vacancy.copy(
            id = UUID.randomUUID(),
            externalId = vacancy.externalId.clean(),
            url = vacancy.url.clean(),
            company = vacancy.company.trim(),
            title = vacancy.title.trim(),
            description = vacancy.description.trim(),
            location = vacancy.location.clean(),
            salaryCurrency = vacancy.salaryCurrency.clean()?.uppercase(),
            createdAt = Instant.now(clock),
        )
        return repository.save(VacancyEntity.from(normalized)).toDomain()
    }

    @Transactional(readOnly = true)
    fun get(id: UUID): Vacancy = repository.findById(id).orElseThrow {
        NotFoundException("VACANCY_NOT_FOUND", "Vacancy not found")
    }.toDomain()

    @Transactional(readOnly = true)
    fun list(): List<Vacancy> = repository.findAllByOrderByCreatedAtDesc().map { it.toDomain() }

    @Transactional
    fun delete(id: UUID) {
        if (!repository.existsById(id)) {
            throw NotFoundException("VACANCY_NOT_FOUND", "Vacancy not found")
        }
        repository.deleteById(id)
    }

    private fun String?.clean() = this?.trim()?.ifBlank { null }
}
