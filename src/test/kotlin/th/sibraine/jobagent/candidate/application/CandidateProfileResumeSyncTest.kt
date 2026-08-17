package th.sibraine.jobagent.candidate.application

import th.sibraine.jobagent.candidate.domain.*
import th.sibraine.jobagent.candidate.infrastructure.CandidateProfileEntity
import th.sibraine.jobagent.candidate.infrastructure.CandidateProfileJpaRepository
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Optional
import java.util.UUID

class CandidateProfileResumeSyncTest {
    private val repository = mockk<CandidateProfileJpaRepository>()
    private val clock = Clock.fixed(Instant.parse("2026-08-17T10:00:00Z"), ZoneOffset.UTC)
    private val service = CandidateProfileService(repository, clock)

    init {
        every { repository.findFirstByActiveTrue() } returns null
    }

    @Test
    fun `confirmed resume replaces imported evidence and preserves explicit user settings and facts`() {
        val existing = CandidateProfileEntity(
            service.profileId,
            CandidateProfile(
                id = service.profileId,
                generalInfo = GeneralInfo("Old Name", "Old headline"),
                roles = listOf("Old role"),
                skills = setOf("Stale resume skill"),
                preferences = JobPreferences(targetRoles = listOf("Staff Engineer"), remoteOnly = true),
                constraints = listOf("No relocation"),
                facts = listOf(
                    CandidateFact("resume:old-skill", FactType.SKILL, "Stale resume skill", true),
                    CandidateFact("manual-kafka", FactType.SKILL, "Kafka", true),
                    CandidateFact("seed-fact-001", FactType.SKILL, "Seed value", true),
                ),
            ),
            Instant.EPOCH,
            Instant.EPOCH,
        )
        every { repository.findById(service.profileId) } returns Optional.of(existing)

        val resume = StructuredResume(
            identity = ResumeIdentity(
                "identity",
                "Ada Lovelace",
                "Backend Engineer",
                confirmedMetadata(),
            ),
            experiences = listOf(
                ResumeExperience(
                    elementId = "experience-1",
                    company = "Analytical Engines",
                    role = "Backend Engineer",
                    achievements = listOf(
                        ResumeTextElement("achievement-1", "Built reliable services", confirmedMetadata())
                    ),
                    metadata = confirmedMetadata(),
                )
            ),
            skills = listOf(ResumeSkill("skill-kotlin", "Kotlin", metadata = confirmedMetadata())),
        )

        val profile = service.syncFromResume(service.profileId, resume)

        assertEquals("Ada Lovelace", profile.generalInfo.displayName)
        assertEquals(listOf("Backend Engineer"), profile.roles)
        assertEquals(setOf("Kotlin", "Kafka"), profile.skills)
        assertEquals(listOf("Staff Engineer"), profile.preferences.targetRoles)
        assertEquals(listOf("No relocation"), profile.constraints)
        assertTrue(profile.facts.any { it.factId == "manual-kafka" })
        assertTrue(profile.facts.any { it.factId == "resume:skill-kotlin" && it.verified })
        assertTrue(profile.facts.any { it.factId == "resume:achievement-1" && it.type == FactType.EXPERIENCE })
        assertFalse(profile.facts.any { it.factId == "resume:old-skill" || it.factId.startsWith("seed-fact-") })
        assertEquals(Instant.parse("2026-08-17T10:00:00Z"), existing.updatedAt)
    }

    private fun confirmedMetadata() =
        ResumeElementMetadata(reviewStatus = ResumeReviewStatus.CONFIRMED)
}
