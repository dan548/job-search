package th.sibraine.jobagent.matching.infrastructure

import th.sibraine.jobagent.shared.ai.StructuredOutputClient
import th.sibraine.jobagent.candidate.domain.CandidateFact
import th.sibraine.jobagent.candidate.domain.CandidateProfile
import th.sibraine.jobagent.candidate.domain.FactType
import th.sibraine.jobagent.candidate.domain.GeneralInfo
import th.sibraine.jobagent.matching.domain.Recommendation
import th.sibraine.jobagent.matching.domain.VacancyAnalysis
import th.sibraine.jobagent.vacancy.domain.Vacancy
import th.sibraine.jobagent.vacancy.domain.VacancySource
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class OpenAiCandidateMatcherTest {
    private val objectMapper = jacksonObjectMapper()

    @Test
    fun `maps structured output and sends only verified facts as evidence`() {
        var capturedInput = ""
        var capturedName = ""
        var capturedSchema: Map<String, Any> = emptyMap()
        val client = StructuredOutputClient { _, input, outputName, schema ->
            capturedInput = input
            capturedName = outputName
            capturedSchema = schema
            """{
              "score":82,"recommendation":"APPLY",
              "matchedRequirements":[{
                "requirement":"Kotlin","strength":1.0,"evidenceFactIds":["fact-verified"]
              }],
              "missingRequirements":[{
                "requirement":"Kafka","importance":"NICE_TO_HAVE"
              }],
              "hardBlockers":[],"reasoningSummary":"Strong verified Kotlin evidence."
            }"""
        }
        val profile = CandidateProfile(
            UUID.randomUUID(),
            GeneralInfo("Private Name"),
            skills = setOf("Kotlin", "Kafka"),
            facts = listOf(
                CandidateFact("fact-verified", FactType.SKILL, "Built Kotlin services", true),
                CandidateFact("fact-unverified", FactType.SKILL, "Used Kafka", false),
            ),
        )

        val result = OpenAiCandidateMatcher(client, objectMapper).match(profile, vacancy(), analysis())

        val input = objectMapper.readTree(capturedInput)
        val facts = input.path("candidate").path("verifiedFacts")
        assertEquals(Recommendation.APPLY, result.recommendation)
        assertEquals(listOf("fact-verified"), result.matchedRequirements.single().evidenceFactIds)
        assertEquals("candidate_match", capturedName)
        assertEquals(false, capturedSchema["additionalProperties"])
        assertEquals(1, facts.size())
        assertEquals("fact-verified", facts.single().path("factId").asText())
        assertFalse(capturedInput.contains("fact-unverified"))
        assertFalse(capturedInput.contains("Private Name"))
    }

    @Test
    fun `prompt treats inputs as untrusted and requires verified fact ids`() {
        assertTrue(OpenAiCandidateMatcher.SYSTEM_PROMPT.contains("untrusted data"))
        assertTrue(OpenAiCandidateMatcher.SYSTEM_PROMPT.contains("exact factId"))
    }

    private fun analysis() = VacancyAnalysis(
        role = "Backend Engineer",
        seniority = "Senior",
        requiredSkills = listOf("Kotlin"),
        preferredSkills = listOf("Kafka"),
    )

    private fun vacancy() = Vacancy(
        UUID.randomUUID(), VacancySource.MANUAL, null, null, "Acme", "Backend Engineer",
        "Kotlin required; Kafka is preferred", null, null, null, null, null, null, Instant.EPOCH,
    )
}
