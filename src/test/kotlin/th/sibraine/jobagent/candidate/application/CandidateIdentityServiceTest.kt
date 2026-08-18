package th.sibraine.jobagent.candidate.application

import th.sibraine.jobagent.candidate.domain.*
import th.sibraine.jobagent.candidate.infrastructure.CandidateProfileEntity
import th.sibraine.jobagent.candidate.infrastructure.CandidateProfileJpaRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Optional
import java.util.UUID

class CandidateIdentityServiceTest {
    private val repository = mockk<CandidateProfileJpaRepository>(relaxed = true)
    private val service = CandidateProfileService(
        repository,
        Clock.fixed(Instant.parse("2026-08-17T10:00:00Z"), ZoneOffset.UTC),
    )

    @Test
    fun `switches active identity after deactivating the current one`() {
        val current = entity("Current", active = true)
        val target = entity("Target", active = false)
        every { repository.findFirstByActiveTrue() } returns current
        every { repository.findById(target.id) } returns Optional.of(target)
        every { repository.saveAndFlush(current) } returns current

        service.activate(target.id)

        assertFalse(current.active)
        assertTrue(target.active)
        verify { repository.saveAndFlush(current) }
    }

    @Test
    fun `manual experience technologies become verified reusable evidence`() {
        val current = entity("Backend", active = true)
        every { repository.findFirstByActiveTrue() } returns current
        every { repository.findById(current.id) } returns Optional.of(current)
        val experience = ResumeExperience(
            elementId = "manual-experience-1",
            company = "Acme",
            role = "Backend Engineer",
            description = "Built event processing",
            metadata = ResumeElementMetadata(
                provenance = ResumeProvenance("Built event processing"),
                reviewStatus = ResumeReviewStatus.CONFIRMED,
            ),
            technologies = listOf("Kotlin", "Kafka"),
        )

        val updated = service.put(current.profile.copy(experiences = listOf(experience)))

        assertTrue("Kotlin" in updated.skills)
        assertTrue("Kafka" in updated.skills)
        assertTrue(updated.facts.any { it.verified && it.type == FactType.EXPERIENCE && it.text.contains("Acme") })
        assertTrue(updated.facts.any { it.verified && it.type == FactType.SKILL && it.text == "Kafka" })
    }

    @Test
    fun `stores an explicitly supplied gap answer as verified evidence without duplicating it`() {
        val current = entity("Backend", active = true)
        every { repository.findFirstByActiveTrue() } returns current
        every { repository.findById(current.id) } returns Optional.of(current)

        service.addConfirmedFact(FactType.EXPERIENCE, "Built Gradle plugins for a multi-module JVM project")
        val updated = service.addConfirmedFact(
            FactType.EXPERIENCE,
            "Built Gradle plugins for a multi-module JVM project",
        )

        val facts = updated.facts.filter { it.text == "Built Gradle plugins for a multi-module JVM project" }
        assertEquals(1, facts.size)
        assertTrue(facts.single().verified)
        assertEquals(FactType.EXPERIENCE, facts.single().type)
    }

    private fun entity(label: String, active: Boolean) = CandidateProfileEntity(
        UUID.randomUUID(),
        CandidateProfile(UUID.randomUUID(), GeneralInfo(label), label = label),
        Instant.EPOCH,
        Instant.EPOCH,
        active,
    ).also { it.profile = it.profile.copy(id = it.id) }
}
