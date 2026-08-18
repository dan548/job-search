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

    @Test
    fun `selects education individually attaches photo and removes projects with excluded experience`() {
        val resume = StructuredResume(
            experiences = listOf(
                ResumeExperience("exp-1", "Acme", "Engineer", metadata = confirmed),
                ResumeExperience("exp-2", "Beta", "Lead", metadata = confirmed),
            ),
            projects = listOf(
                ResumeProject("project-1", "Acme Platform", metadata = confirmed, experienceElementId = "exp-1"),
                ResumeProject("project-2", "Independent", metadata = confirmed),
            ),
            education = listOf(
                ResumeEducation("edu-1", "University One", degree = "BSc", metadata = confirmed),
                ResumeEducation("edu-2", "University Two", degree = "MSc", metadata = confirmed),
            ),
        )
        val photo = "data:image/png;base64,iVBORw0KGgo="

        val selected = CanonicalResumeAssembler().select(
            resume,
            ResumeContentSelectionRequest(
                experienceElementIds = listOf("exp-2"),
                educationElementIds = listOf("edu-2"),
                photoDataUri = photo,
            ),
        )

        assertEquals(listOf("exp-2"), selected.experiences.map { it.elementId })
        assertEquals(listOf("project-2"), selected.projects.map { it.elementId })
        assertEquals(listOf("edu-2"), selected.education.map { it.elementId })
        assertEquals(photo, selected.photo?.dataUri)
    }
}
