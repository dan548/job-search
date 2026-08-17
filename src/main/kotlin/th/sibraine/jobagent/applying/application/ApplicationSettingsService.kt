package th.sibraine.jobagent.applying.application

import th.sibraine.jobagent.applying.domain.AnswerCatalogEntry
import th.sibraine.jobagent.applying.domain.ApplicationSettings
import th.sibraine.jobagent.applying.domain.FormFieldTopic
import th.sibraine.jobagent.applying.infrastructure.AnswerCatalogEntity
import th.sibraine.jobagent.applying.infrastructure.AnswerCatalogJpaRepository
import th.sibraine.jobagent.applying.infrastructure.ApplicationSettingsEntity
import th.sibraine.jobagent.applying.infrastructure.ApplicationSettingsJpaRepository
import th.sibraine.jobagent.candidate.application.CandidateProfileService
import th.sibraine.jobagent.shared.NotFoundException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.UUID

/**
 * Explicit application settings and the catalog of reusable answers. Both are user-owned inputs:
 * nothing here is inferred from the resume, the vacancy or previous applications.
 */
@Service
class ApplicationSettingsService(
    private val settingsRepository: ApplicationSettingsJpaRepository,
    private val catalogRepository: AnswerCatalogJpaRepository,
    private val profiles: CandidateProfileService,
    private val clock: Clock,
) {
    private val profileId: UUID get() = profiles.profileId

    @Transactional(readOnly = true)
    fun settings(): ApplicationSettings = settingsRepository.findById(profileId).map { it.settings }
        .orElse(ApplicationSettings())

    @Transactional
    fun putSettings(value: ApplicationSettings): ApplicationSettings {
        validate(value)
        val now = Instant.now(clock)
        val entity = settingsRepository.findById(profileId).orElse(null)
        if (entity == null) settingsRepository.save(ApplicationSettingsEntity(profileId, value, now))
        else {
            entity.settings = value
            entity.updatedAt = now
        }
        return value
    }

    @Transactional(readOnly = true)
    fun catalog(): List<AnswerCatalogEntry> = catalogRepository
        .findByCandidateProfileIdOrderByAnswerKeyAsc(profileId)
        .map { it.toDomain() }

    @Transactional
    fun putCatalogEntry(entry: AnswerCatalogEntry): AnswerCatalogEntry {
        require(entry.key.isNotBlank()) { "Answer catalog key must not be blank" }
        require(entry.key.length <= 160) { "Answer catalog key must not exceed 160 characters" }
        require(entry.value.isNotBlank()) { "Answer catalog value must not be blank" }
        val now = Instant.now(clock)
        val existing = catalogRepository.findByCandidateProfileIdAndAnswerKey(profileId, entry.key)
        val saved = if (existing == null) {
            catalogRepository.save(
                AnswerCatalogEntity(
                    id = UUID.randomUUID(),
                    candidateProfileId = profileId,
                    answerKey = entry.key,
                    question = entry.question,
                    value = entry.value,
                    topic = entry.topic,
                    updatedAt = now,
                )
            )
        } else {
            existing.question = entry.question
            existing.value = entry.value
            existing.topic = entry.topic
            existing.updatedAt = now
            existing
        }
        return saved.toDomain()
    }

    @Transactional
    fun deleteCatalogEntry(key: String) {
        val existing = catalogRepository.findByCandidateProfileIdAndAnswerKey(profileId, key)
            ?: throw NotFoundException("ANSWER_CATALOG_ENTRY_NOT_FOUND", "Answer catalog entry not found")
        catalogRepository.delete(existing)
    }

    /** Remembers a user answer so the same question is not asked again on the next application. */
    @Transactional
    fun remember(key: String, question: String, value: String, topic: FormFieldTopic) {
        putCatalogEntry(AnswerCatalogEntry(key = key, question = question, value = value, topic = topic))
    }

    private fun validate(value: ApplicationSettings) {
        value.desiredSalary?.let { salary ->
            require(salary.amount.signum() > 0) { "Desired salary amount must be positive" }
            require(salary.currency.length == 3) { "Desired salary currency must be a 3-letter code" }
        }
        require(value.workAuthorizations.all { it.country.isNotBlank() && it.status.isNotBlank() }) {
            "Work authorization country and status must not be blank"
        }
        value.earliestStartDate?.let { date ->
            try {
                LocalDate.parse(date)
            } catch (error: DateTimeParseException) {
                throw IllegalArgumentException("Earliest start date must be an ISO date (yyyy-MM-dd)", error)
            }
        }
    }
}
