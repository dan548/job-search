package th.sibraine.jobagent.candidate.application

import th.sibraine.jobagent.candidate.domain.*
import th.sibraine.jobagent.candidate.infrastructure.ResumeImportEntity
import th.sibraine.jobagent.candidate.infrastructure.ResumeImportJpaRepository
import th.sibraine.jobagent.shared.ConflictException
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class ResumeImportServiceTest {
    private val parser = mockk<ResumeDocumentParser>()
    private val decomposer = mockk<ResumeDecomposer>()
    private val imports = mockk<ResumeImportJpaRepository>()
    private val profiles = mockk<CandidateProfileService>(relaxed = true)
    private val clock = Clock.fixed(Instant.parse("2026-08-17T10:00:00Z"), ZoneOffset.UTC)
    private val service = ResumeImportService(parser, decomposer, imports, StructuredResumeValidator(), profiles, clock)

    @Test
    fun `creates persisted versioned preview without storing verified data`() {
        every { parser.parse(any()) } returns ParsedResume(
            fileName = "resume.pdf",
            pageCount = 2,
            extractedText = "Backend Engineer\nKotlin",
            detectedSkills = listOf("Kotlin"),
            factCandidates = listOf(CandidateFact("resume-kotlin", FactType.SKILL, "Kotlin", false)),
            warnings = listOf("Review imported facts"),
        )
        every { decomposer.decompose(any()) } returns StructuredResume(
            skills = listOf(ResumeSkill("resume-kotlin", "Kotlin"))
        )
        every { imports.save(any()) } answers { firstArg<ResumeImportEntity>().apply { version = 3 } }

        val preview = service.createPreview("../resume.pdf", "%PDF-content".toByteArray())

        assertEquals(3, preview.version)
        assertEquals("resume.pdf", preview.fileName)
        assertEquals(ResumeImportStatus.PREVIEW, preview.status)
        assertEquals(64, preview.sourceSha256.length)
        assertEquals("Kotlin", preview.structuredResume.skills.single().name)
        assertEquals(
            ResumeReviewStatus.UNREVIEWED,
            preview.structuredResume.skills.single().metadata.reviewStatus,
        )
        verify { imports.save(match { it.sourcePdf.contentEquals("%PDF-content".toByteArray()) }) }
    }

    @Test
    fun `confirms a fully reviewed snapshot`() {
        val importId = UUID.randomUUID()
        val entity = previewEntity(importId)
        val reviewed = StructuredResume(
            skills = listOf(
                ResumeSkill(
                    "skill-kotlin",
                    "Kotlin",
                    metadata = ResumeElementMetadata(reviewStatus = ResumeReviewStatus.CONFIRMED),
                )
            )
        )
        every { imports.findByImportId(importId) } returns entity

        val confirmed = service.confirm(importId, reviewed)

        assertEquals(ResumeImportStatus.CONFIRMED, confirmed.status)
        assertEquals(Instant.parse("2026-08-17T10:00:00Z"), confirmed.confirmedAt)
        assertEquals(reviewed, confirmed.structuredResume)
        verify { profiles.syncFromResume(any(), reviewed) }
    }

    @Test
    fun `enrichment keeps canonical facts and adds confirmed detail from another resume`() {
        val importId = UUID.randomUUID()
        val entity = previewEntity(importId)
        val canonical = StructuredResume(
            summary = ResumeTextElement("summary-current", "Staff engineer", confirmedMetadata()),
            skills = listOf(ResumeSkill("skill-kotlin", "Kotlin", metadata = confirmedMetadata())),
        )
        val addition = StructuredResume(
            summary = ResumeTextElement("summary-old", "Backend engineer", confirmedMetadata()),
            skills = listOf(ResumeSkill("skill-kafka", "Kafka", metadata = confirmedMetadata())),
        )
        every { imports.findByImportId(importId) } returns entity
        every {
            imports.findFirstByCandidateProfileIdAndStatusOrderByVersionDesc(any(), ResumeImportStatus.CONFIRMED)
        } returns previewEntity(UUID.randomUUID()).apply {
            structuredResume = canonical
            status = ResumeImportStatus.CONFIRMED
        }

        val result = service.confirm(importId, addition, ResumeConfirmationMode.ENRICH)

        assertEquals("Staff engineer", result.structuredResume.summary?.text)
        assertEquals(listOf("Kotlin", "Kafka"), result.structuredResume.skills.map { it.name })
        verify { profiles.syncFromResume(any(), result.structuredResume) }
    }

    @Test
    fun `confirmation rejects an unreviewed snapshot`() {
        assertThrows<IllegalArgumentException> {
            service.confirm(
                UUID.randomUUID(),
                StructuredResume(skills = listOf(ResumeSkill("skill-kotlin", "Kotlin"))),
            )
        }
        verify(exactly = 0) { imports.findByImportId(any()) }
    }

    @Test
    fun `confirmed import is immutable but same retry is idempotent`() {
        val importId = UUID.randomUUID()
        val reviewed = StructuredResume(
            summary = ResumeTextElement(
                "summary",
                "Backend engineer",
                ResumeElementMetadata(reviewStatus = ResumeReviewStatus.CONFIRMED),
            )
        )
        val entity = previewEntity(importId).apply {
            structuredResume = reviewed
            status = ResumeImportStatus.CONFIRMED
            confirmedAt = Instant.EPOCH
        }
        every { imports.findByImportId(importId) } returns entity

        assertEquals(reviewed, service.confirm(importId, reviewed).structuredResume)
        assertThrows<ConflictException> {
            service.confirm(
                importId,
                reviewed.copy(summary = reviewed.summary!!.copy(text = "Changed")),
            )
        }
    }

    private fun previewEntity(importId: UUID) = ResumeImportEntity(
        version = 1,
        importId = importId,
        candidateProfileId = UUID.fromString(SINGLE_USER_PROFILE_ID),
        fileName = "resume.pdf",
        sourceSha256 = "0".repeat(64),
        sourcePdf = "%PDF".toByteArray(),
        pageCount = 1,
        extractedText = "Kotlin",
        warnings = emptyList(),
        structuredResume = StructuredResume(),
        status = ResumeImportStatus.PREVIEW,
        createdAt = Instant.EPOCH,
    )

    private fun confirmedMetadata() =
        ResumeElementMetadata(reviewStatus = ResumeReviewStatus.CONFIRMED)
}
