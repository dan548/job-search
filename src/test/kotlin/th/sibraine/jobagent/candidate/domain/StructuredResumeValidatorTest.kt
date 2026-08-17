package th.sibraine.jobagent.candidate.domain

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class StructuredResumeValidatorTest {
    private val validator = StructuredResumeValidator()

    @Test
    fun `accepts reviewed resume with provenance and valid references`() {
        val metadata = ResumeElementMetadata(
            provenance = ResumeProvenance("Kotlin", pageNumber = 1),
            confidence = 0.95,
            reviewStatus = ResumeReviewStatus.CONFIRMED,
        )
        val resume = StructuredResume(
            skills = listOf(ResumeSkill("skill-kotlin", "Kotlin", metadata = metadata)),
            projects = listOf(
                ResumeProject(
                    elementId = "project-agent",
                    name = "Job Agent",
                    skillElementIds = listOf("skill-kotlin"),
                    metadata = metadata,
                )
            ),
        )

        assertDoesNotThrow { validator.validate(resume, requireReviewed = true) }
    }

    @Test
    fun `rejects duplicate IDs including nested achievements`() {
        val resume = StructuredResume(
            summary = ResumeTextElement("duplicate", "Summary"),
            experiences = listOf(
                ResumeExperience(
                    elementId = "experience-1",
                    company = "Acme",
                    role = "Engineer",
                    achievements = listOf(ResumeTextElement("duplicate", "Built a service")),
                )
            ),
        )

        assertThrows<IllegalArgumentException> { validator.validate(resume) }
    }

    @Test
    fun `requires every element to be reviewed before confirmation`() {
        val resume = StructuredResume(skills = listOf(ResumeSkill("skill-kotlin", "Kotlin")))

        assertThrows<IllegalArgumentException> { validator.validate(resume, requireReviewed = true) }
    }

    @Test
    fun `rejects project reference to unknown skill`() {
        val resume = StructuredResume(
            projects = listOf(ResumeProject("project-1", "Project", skillElementIds = listOf("missing")))
        )

        assertThrows<IllegalArgumentException> { validator.validate(resume) }
    }

    @Test
    fun `rejects contradictory experience dates`() {
        val resume = StructuredResume(
            experiences = listOf(
                ResumeExperience(
                    "experience-1",
                    "Acme",
                    "Engineer",
                    startDate = ResumeDate(2025, 1),
                    endDate = ResumeDate(2024, 12),
                )
            )
        )

        assertThrows<IllegalArgumentException> { validator.validate(resume) }
    }
}
