package th.sibraine.jobagent.tailoring.infrastructure

import th.sibraine.jobagent.candidate.domain.*
import th.sibraine.jobagent.matching.domain.*
import th.sibraine.jobagent.shared.ai.StructuredOutputClient
import th.sibraine.jobagent.tailoring.domain.EvidenceKind
import th.sibraine.jobagent.tailoring.domain.EvidenceRef
import th.sibraine.jobagent.tailoring.domain.TailoringRequest
import th.sibraine.jobagent.vacancy.domain.Vacancy
import th.sibraine.jobagent.vacancy.domain.VacancySource
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class OpenAiResumeTailorTest {
    private val objectMapper = jacksonObjectMapper()

    @Test
    fun `maps the plan and sends only confirmed elements and verified facts`() {
        var capturedInput = ""
        var capturedName = ""
        var capturedSchema: Map<String, Any> = emptyMap()
        val client = StructuredOutputClient { _, input, outputName, schema ->
            capturedInput = input
            capturedName = outputName
            capturedSchema = schema
            """{
              "summary":{
                "text":"Backend engineer building Kotlin services",
                "evidence":[{"kind":"RESUME_ELEMENT","id":"summary-1"}],
                "sourceElementId":"summary-1","addressedRequirements":["Kotlin"]
              },
              "experiences":[{
                "sourceElementId":"exp-1",
                "achievements":[{
                  "text":"Wrote Kotlin services",
                  "evidence":[{"kind":"PROFILE_FACT","id":"fact-verified"}],
                  "sourceElementId":"ach-1","addressedRequirements":["Kotlin"]
                }]
              }],
              "projects":[],"skillElementIds":["skill-kotlin"],
              "rationale":"Kotlin evidence first."
            }"""
        }

        val plan = OpenAiResumeTailor(client, objectMapper).tailor(request())

        val input = objectMapper.readTree(capturedInput)
        assertEquals("resume_tailoring_plan", capturedName)
        assertEquals(false, capturedSchema["additionalProperties"])
        assertEquals("summary-1", plan.summary?.sourceElementId)
        assertEquals(
            listOf(EvidenceRef(EvidenceKind.PROFILE_FACT, "fact-verified")),
            plan.experiences.single().achievements.single().evidence,
        )
        assertEquals(listOf("skill-kotlin"), plan.skillElementIds)
        assertEquals(1, input.path("verifiedFacts").size())
        assertEquals("Kotlin", input.path("requirements").single().path("requirement").asText())
        assertFalse(capturedInput.contains("fact-unverified"))
    }

    @Test
    fun `prompt forbids new claims and requires exact evidence ids`() {
        assertTrue(OpenAiResumeTailor.SYSTEM_PROMPT.contains("untrusted data"))
        assertTrue(OpenAiResumeTailor.SYSTEM_PROMPT.contains("Never add a claim"))
        assertTrue(OpenAiResumeTailor.SYSTEM_PROMPT.contains("exact id"))
        assertTrue(OpenAiResumeTailor.SYSTEM_PROMPT.contains("Never put an experience or project elementId"))
        assertTrue(OpenAiResumeTailor.SYSTEM_PROMPT.contains("sourceElementId at most once"))
    }

    @Test
    fun `repairs a parent experience id used as an achievement source`() {
        val client = StructuredOutputClient { _, _, _, _ ->
            """{
              "summary":null,
              "experiences":[{
                "sourceElementId":"exp-1",
                "achievements":[{
                  "text":"Wrote Kotlin services",
                  "evidence":[{"kind":"RESUME_ELEMENT","id":"ach-1"}],
                  "sourceElementId":"exp-1",
                  "addressedRequirements":["Kotlin"]
                }]
              }],
              "projects":[],"skillElementIds":["skill-kotlin"],"rationale":"Kotlin first."
            }"""
        }

        val plan = OpenAiResumeTailor(client, objectMapper).tailor(request())

        assertNull(plan.experiences.single().achievements.single().sourceElementId)
        assertEquals("ach-1", plan.experiences.single().achievements.single().evidence.single().id)
    }

    @Test
    fun `merges repeated experience sections and removes repeated achievement sources`() {
        val client = StructuredOutputClient { _, _, _, _ ->
            """{
              "summary":null,
              "experiences":[
                {"sourceElementId":"exp-1","achievements":[{
                  "text":"Wrote Kotlin services",
                  "evidence":[{"kind":"RESUME_ELEMENT","id":"ach-1"}],
                  "sourceElementId":"ach-1","addressedRequirements":["Kotlin"]
                }]},
                {"sourceElementId":"exp-1","achievements":[{
                  "text":"Built backend services in Kotlin",
                  "evidence":[{"kind":"RESUME_ELEMENT","id":"ach-1"}],
                  "sourceElementId":"ach-1","addressedRequirements":["Kotlin"]
                }]}
              ],
              "projects":[],"skillElementIds":["skill-kotlin","skill-kotlin"],
              "rationale":"Kotlin first."
            }"""
        }

        val plan = OpenAiResumeTailor(client, objectMapper).tailor(request())

        assertEquals(1, plan.experiences.size)
        assertEquals("exp-1", plan.experiences.single().sourceElementId)
        assertEquals(listOf("Wrote Kotlin services"), plan.experiences.single().achievements.map { it.text })
        assertEquals(listOf("skill-kotlin"), plan.skillElementIds)
    }

    private fun request() = TailoringRequest(
        vacancy = Vacancy(
            UUID.randomUUID(), VacancySource.MANUAL, null, null, "Acme", "Backend Engineer",
            "Kotlin required", null, null, null, null, null, null, Instant.EPOCH,
        ),
        analysis = VacancyAnalysis(role = "Backend Engineer", seniority = null, requiredSkills = listOf("Kotlin")),
        match = MatchResult(
            score = 70,
            recommendation = Recommendation.APPLY,
            reasoningSummary = "test",
            requirementEvidenceMatrix = listOf(
                RequirementEvidenceRow(
                    requirement = "Kotlin",
                    importance = RequirementImportance.HARD_REQUIREMENT,
                    sources = listOf(RequirementSource.REQUIRED_SKILL),
                    status = RequirementStatus.MATCHED,
                )
            ),
        ),
        profile = CandidateProfile(
            UUID.randomUUID(),
            GeneralInfo("Test Candidate"),
            facts = listOf(
                CandidateFact("fact-verified", FactType.SKILL, "Built Kotlin services", true),
                CandidateFact("fact-unverified", FactType.SKILL, "Used Kafka", false),
            ),
        ),
        resume = StructuredResume(
            summary = ResumeTextElement("summary-1", "Backend engineer", confirmed()),
            experiences = listOf(
                ResumeExperience(
                    elementId = "exp-1",
                    company = "Acme",
                    role = "Backend Engineer",
                    achievements = listOf(ResumeTextElement("ach-1", "Kotlin services", confirmed())),
                    metadata = confirmed(),
                )
            ),
            skills = listOf(ResumeSkill("skill-kotlin", "Kotlin", metadata = confirmed())),
        ),
    )

    private fun confirmed() = ResumeElementMetadata(reviewStatus = ResumeReviewStatus.CONFIRMED)
}
