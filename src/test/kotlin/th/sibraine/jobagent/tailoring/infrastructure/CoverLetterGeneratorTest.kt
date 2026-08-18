package th.sibraine.jobagent.tailoring.infrastructure

import th.sibraine.jobagent.candidate.domain.ResumeElementMetadata
import th.sibraine.jobagent.candidate.domain.ResumeIdentity
import th.sibraine.jobagent.candidate.domain.ResumeReviewStatus
import th.sibraine.jobagent.candidate.domain.ResumeSkill
import th.sibraine.jobagent.candidate.domain.StructuredResume
import th.sibraine.jobagent.shared.ai.StructuredOutputClient
import th.sibraine.jobagent.tailoring.domain.CoverLetterRequest
import th.sibraine.jobagent.vacancy.domain.Vacancy
import th.sibraine.jobagent.vacancy.domain.VacancySource
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class CoverLetterGeneratorTest {
    @Test
    fun `deterministic generator uses the vacancy and tailored resume`() {
        val result = DeterministicCoverLetterGenerator().generate(request())

        assertTrue(result.contains("Backend Engineer"))
        assertTrue(result.contains("Example"))
        assertTrue(result.contains("Kotlin"))
        assertTrue(result.contains("Test Candidate"))
    }

    @Test
    fun `openai generator sends only vacancy and tailored resume and returns text`() {
        var prompt = ""
        var input = ""
        var name = ""
        var schema: Map<String, Any> = emptyMap()
        val client = StructuredOutputClient { systemPrompt, payload, outputName, outputSchema ->
            prompt = systemPrompt
            input = payload
            name = outputName
            schema = outputSchema
            """{"text":"A focused cover letter."}"""
        }

        val result = OpenAiCoverLetterGenerator(client, jacksonObjectMapper().findAndRegisterModules()).generate(request())

        assertEquals("A focused cover letter.", result)
        assertEquals("cover_letter", name)
        assertEquals(false, schema["additionalProperties"])
        assertTrue(input.contains("tailoredResume"))
        assertTrue(input.contains("Backend Engineer"))
        assertTrue(prompt.contains("Never invent"))
        assertTrue(prompt.contains("untrusted data"))
        assertFalse(prompt.contains("perfect candidate"))
    }

    private fun request() = CoverLetterRequest(
        vacancy = Vacancy(
            id = UUID.randomUUID(),
            source = VacancySource.MANUAL,
            externalId = null,
            url = null,
            company = "Example",
            title = "Backend Engineer",
            description = "Build Kotlin services.",
            location = "Remote",
            employmentType = null,
            salaryFrom = null,
            salaryTo = null,
            salaryCurrency = null,
            publishedAt = null,
            createdAt = Instant.EPOCH,
        ),
        resume = StructuredResume(
            identity = ResumeIdentity("identity-1", "Test Candidate", "Backend Engineer", confirmed()),
            skills = listOf(ResumeSkill("skill-1", "Kotlin", metadata = confirmed())),
        ),
    )

    private fun confirmed() = ResumeElementMetadata(reviewStatus = ResumeReviewStatus.CONFIRMED)
}
