package th.sibraine.jobagent.tailoring.domain

import th.sibraine.jobagent.candidate.domain.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class ResumeContentSelectionTest {
    private val confirmed = ResumeElementMetadata(reviewStatus = ResumeReviewStatus.CONFIRMED)

    @Test
    fun `assembles manual profile data and allows explicit exclusions`() {
        val experience = ResumeExperience(
            "manual-experience-1", "Acme", "Backend Engineer",
            description = "Built Kafka services", metadata = confirmed,
        )
        val profile = CandidateProfile(
            UUID.randomUUID(),
            GeneralInfo("Ada Lovelace", "Backend Engineer"),
            skills = setOf("Kotlin", "Kafka"),
            contacts = listOf(
                ResumeContact("manual-email-1", ResumeContactType.EMAIL, "work@example.com", metadata = confirmed),
                ResumeContact("manual-email-2", ResumeContactType.EMAIL, "personal@example.com", metadata = confirmed),
            ),
            experiences = listOf(experience),
        )
        val assembler = CanonicalResumeAssembler()
        val resume = assembler.assemble(profile, null)
        val options = assembler.options(resume, "Senior Kafka Engineer")

        assertEquals("Ada Lovelace", resume.identity?.fullName)
        assertEquals(listOf("Kotlin", "Kafka"), resume.skills.map { it.name })
        assertTrue(options.skills.single { it.title == "Kafka" }.selectedByDefault)
        assertFalse(options.skills.single { it.title == "Kotlin" }.selectedByDefault)
        assertEquals(1, options.contacts.count { it.selectedByDefault })

        val selected = assembler.select(
            resume,
            ResumeContentSelectionRequest(
                contactElementIds = listOf("manual-email-2"),
                experienceElementIds = emptyList(),
                skillElementIds = resume.skills.filter { it.name == "Kafka" }.map { it.elementId },
            ),
        )

        assertEquals(listOf("personal@example.com"), selected.contacts.map { it.value })
        assertTrue(selected.experiences.isEmpty())
        assertEquals(listOf("Kafka"), selected.skills.map { it.name })
    }
}
