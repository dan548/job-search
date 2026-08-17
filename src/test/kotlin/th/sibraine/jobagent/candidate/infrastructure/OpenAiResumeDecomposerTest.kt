package th.sibraine.jobagent.candidate.infrastructure

import th.sibraine.jobagent.candidate.domain.*
import th.sibraine.jobagent.shared.ai.AiAnalysisException
import th.sibraine.jobagent.shared.ai.StructuredOutputClient
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class OpenAiResumeDecomposerTest {
    private val mapper = jacksonObjectMapper().findAndRegisterModules()
    private val blocks = listOf(
        ResumeTextBlock("block-name", 1, 0, "Ada Lovelace - Backend Engineer", ResumeBoundingBox(10.0, 10.0, 250.0, 15.0)),
        ResumeTextBlock("block-job", 1, 1, "Acme | Senior Engineer | 2021 - present", ResumeBoundingBox(10.0, 40.0, 300.0, 15.0)),
        ResumeTextBlock("block-achievement", 1, 2, "Built Kotlin services", ResumeBoundingBox(20.0, 60.0, 180.0, 15.0)),
        ResumeTextBlock("block-skills", 1, 3, "Kotlin, PostgreSQL", ResumeBoundingBox(10.0, 100.0, 200.0, 15.0)),
    )

    @Test
    fun `maps strict decomposition to stable resume with server owned provenance`() {
        val client = StructuredOutputClient { _, input, outputName, schema ->
            assertEquals("structured_resume", outputName)
            assertTrue(input.contains("block-achievement"))
            assertEquals(false, schema["additionalProperties"])
            decompositionJson()
        }
        val decomposer = OpenAiResumeDecomposer(client, mapper, StructuredResumeValidator())
        val parsed = parsedResume(blocks)

        val first = decomposer.decompose(parsed)
        val second = decomposer.decompose(parsed)

        assertEquals(first, second)
        assertEquals("Ada Lovelace", first.identity!!.fullName)
        assertEquals(1, first.experiences.size)
        assertTrue(first.experiences.single().current)
        assertEquals("Built Kotlin services", first.experiences.single().achievements.single().text)
        assertEquals(2, first.skills.size)
        assertEquals(1, first.skills.count { it.name == "Kotlin" })
        val provenance = first.experiences.single().achievements.single().metadata.provenance!!
        assertEquals("Built Kotlin services", provenance.sourceText)
        assertEquals(1, provenance.pageNumber)
        assertEquals(20.0, provenance.boundingBox!!.x)
        assertEquals(ResumeReviewStatus.UNREVIEWED, first.identity!!.metadata.reviewStatus)
    }

    @Test
    fun `rejects source block IDs not present in parsed resume`() {
        val invalid = decompositionJson().replace("block-name", "block-invented")
        val decomposer = OpenAiResumeDecomposer(
            StructuredOutputClient { _, _, _, _ -> invalid },
            mapper,
            StructuredResumeValidator(),
        )

        assertThrows<AiAnalysisException> { decomposer.decompose(parsedResume(blocks)) }
    }

    private fun parsedResume(blocks: List<ResumeTextBlock>) = ParsedResume(
        "resume.pdf",
        1,
        blocks.joinToString("\n") { it.text },
        listOf("Kotlin", "PostgreSQL"),
        emptyList(),
        textBlocks = blocks,
    )

    private fun decompositionJson() = """{
      "identity": {
        "fullName": "Ada Lovelace", "headline": "Backend Engineer",
        "sourceBlockIds": ["block-name"], "confidence": 0.99
      },
      "summary": null,
      "contacts": [],
      "experiences": [{
        "company": "Acme", "role": "Senior Engineer", "location": null,
        "startDate": {"year": 2021, "month": null}, "endDate": null, "current": true,
        "description": null,
        "achievements": [{
          "text": "Built Kotlin services", "sourceBlockIds": ["block-achievement"], "confidence": 0.96
        }],
        "sourceBlockIds": ["block-job"], "confidence": 0.98
      }],
      "projects": [],
      "education": [],
      "certifications": [],
      "languages": [],
      "skills": [
        {"name": "Kotlin", "category": "Backend", "sourceBlockIds": ["block-skills"], "confidence": 0.99},
        {"name": "kotlin", "category": null, "sourceBlockIds": ["block-skills"], "confidence": 0.80},
        {"name": "PostgreSQL", "category": "Database", "sourceBlockIds": ["block-skills"], "confidence": 0.99}
      ]
    }"""
}
