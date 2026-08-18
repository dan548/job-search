package th.sibraine.jobagent.vacancy.application

import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import th.sibraine.jobagent.vacancy.domain.EmploymentType
import th.sibraine.jobagent.vacancy.domain.Vacancy
import th.sibraine.jobagent.vacancy.domain.VacancySource
import th.sibraine.jobagent.vacancy.infrastructure.VacancyEntity
import th.sibraine.jobagent.vacancy.infrastructure.VacancyJpaRepository
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class VacancyServiceTest {
    private val repository = mockk<VacancyJpaRepository>()
    private val now = Instant.parse("2026-08-18T06:00:00Z")
    private val service = VacancyService(repository, Clock.fixed(now, ZoneOffset.UTC))

    @Test
    fun `normalizes manually entered vacancy metadata`() {
        every { repository.save(any()) } answers { firstArg<VacancyEntity>() }

        val saved = service.create(
            Vacancy(
                id = UUID(0, 0),
                source = VacancySource.MANUAL,
                externalId = " ",
                url = " https://example.com/job ",
                company = " Acme ",
                title = " Backend Engineer ",
                description = " Role description ",
                location = " Remote ",
                employmentType = EmploymentType.CONTRACT,
                salaryFrom = null,
                salaryTo = null,
                salaryCurrency = " usd ",
                publishedAt = null,
                createdAt = Instant.EPOCH,
            ),
        )

        assertEquals("Acme", saved.company)
        assertEquals("Backend Engineer", saved.title)
        assertEquals("Role description", saved.description)
        assertEquals("Remote", saved.location)
        assertEquals(EmploymentType.CONTRACT, saved.employmentType)
        assertEquals("USD", saved.salaryCurrency)
        assertNull(saved.externalId)
        assertEquals(now, saved.createdAt)
    }
}
