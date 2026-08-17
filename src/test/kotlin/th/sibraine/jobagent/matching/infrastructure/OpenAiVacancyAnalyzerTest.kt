package th.sibraine.jobagent.matching.infrastructure

import th.sibraine.jobagent.shared.ai.StructuredOutputClient
import th.sibraine.jobagent.vacancy.domain.Vacancy
import th.sibraine.jobagent.vacancy.domain.VacancySource
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

class OpenAiVacancyAnalyzerTest {
    private val objectMapper = jacksonObjectMapper()

    @Test
    fun `maps structured model output to vacancy analysis`() {
        var capturedInput = ""
        var capturedSchema: Map<String, Any> = emptyMap()
        val client = StructuredOutputClient { _, input, outputName, schema ->
            capturedInput = input
            capturedSchema = schema
            assertEquals("vacancy_analysis", outputName)
            """{
              "role":"Senior Kotlin Engineer","seniority":"Senior",
              "requiredSkills":["Kotlin","PostgreSQL"],"preferredSkills":["Kafka"],
              "responsibilities":["Build backend services"],"hardRequirements":[],
              "softRequirements":["Communication"],"niceToHave":["Kafka"],
              "recruiterFluff":[],"languageRequirements":["English B2"],
              "locationConstraints":["Remote in EU"],"workAuthorizationConstraints":["EU work permit"],
              "salary":{"from":"5000","to":"7000","currency":"EUR"}
            }"""
        }
        val vacancy = Vacancy(
            UUID.randomUUID(), VacancySource.MANUAL, null, null, "Acme",
            "Senior Kotlin Engineer", "Kotlin required. Kafka is a plus.", "Remote in EU",
            null, BigDecimal("5000"), BigDecimal("7000"), "EUR", null, Instant.now(),
        )

        val result = OpenAiVacancyAnalyzer(client, objectMapper).analyze(vacancy)

        assertEquals(listOf("Kotlin", "PostgreSQL"), result.requiredSkills)
        assertEquals("5000", result.salary?.from)
        assertTrue(objectMapper.readTree(capturedInput).path("description").asText().contains("Kafka"))
        assertEquals(false, capturedSchema["additionalProperties"])
    }

    @Test
    fun `prompt marks vacancy text as untrusted data`() {
        assertTrue(OpenAiVacancyAnalyzer.SYSTEM_PROMPT.contains("untrusted data"))
    }
}
