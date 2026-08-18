package th.sibraine.jobagent.tailoring.application

import th.sibraine.jobagent.candidate.application.ResumeImportService
import th.sibraine.jobagent.candidate.domain.*
import th.sibraine.jobagent.candidate.infrastructure.CandidateProfileEntity
import th.sibraine.jobagent.candidate.infrastructure.CandidateProfileJpaRepository
import th.sibraine.jobagent.matching.application.AnalysisResult
import th.sibraine.jobagent.matching.application.AnalyzeVacancyUseCase
import th.sibraine.jobagent.matching.domain.*
import th.sibraine.jobagent.tailoring.domain.*
import th.sibraine.jobagent.tailoring.infrastructure.ResumeVariantEntity
import th.sibraine.jobagent.tailoring.infrastructure.ResumeVariantJpaRepository
import th.sibraine.jobagent.vacancy.application.VacancyService
import th.sibraine.jobagent.vacancy.domain.Vacancy
import th.sibraine.jobagent.vacancy.domain.VacancySource
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Optional
import java.util.UUID

class TailorResumeUseCaseTest {
    private val profiles = mockk<CandidateProfileJpaRepository>()
    private val vacancies = mockk<VacancyService>()
    private val analyzeVacancy = mockk<AnalyzeVacancyUseCase>()
    private val resumeImports = mockk<ResumeImportService>()
    private val variants = mockk<ResumeVariantJpaRepository>()
    private val clock = Clock.fixed(Instant.parse("2026-08-17T10:00:00Z"), ZoneOffset.UTC)
    private val profileId = UUID.randomUUID()
    private val vacancyId = UUID.randomUUID()
    private val importId = UUID.randomUUID()

    @Test
    fun `tailors only confirmed content and reports omissions, gaps and questions`() {
        var seenResume: StructuredResume? = null
        val useCase = useCase { request ->
            seenResume = request.resume
            TailoringPlan(
                summary = reuse("summary-1", "Backend engineer"),
                experiences = listOf(TailoredExperience("exp-1", listOf(reuse("ach-kotlin", "Wrote Kotlin services")))),
                skillElementIds = listOf("skill-kotlin"),
                rationale = "Kotlin evidence first.",
            )
        }
        stubDependencies()

        val variant = useCase.execute(profileId, vacancyId)

        assertEquals(
            listOf("ach-kotlin", "ach-office"),
            seenResume!!.experiences.single().achievements.map { it.elementId },
        )
        assertEquals(listOf("skill-kotlin"), seenResume!!.skills.map { it.elementId })
        assertEquals(7, variant.version)
        assertEquals(importId, variant.baseImportId)
        assertEquals(4, variant.baseImportVersion)
        assertEquals(CURRENT_TEMPLATE_ID, variant.templateId)
        assertEquals(CURRENT_TEMPLATE_VERSION, variant.templateVersion)
        assertNull(variant.reviewedAt)
        assertEquals(
            listOf("ach-rejected" to OmissionReason.NOT_CONFIRMED, "ach-office" to OmissionReason.NOT_SELECTED),
            variant.plan.omissions.map { it.elementId to it.reason }.sortedBy { it.second.name },
        )
        assertEquals(listOf("Kafka"), variant.plan.gaps.map { it.requirement })
        assertEquals(listOf("Kafka"), variant.plan.questions.map { it.requirement })
        assertEquals(
            listOf("ach-office" to ResumeChangeType.REMOVED),
            variant.diff.filter { it.section == "EXPERIENCE_ACHIEVEMENT" }.map { it.elementId to it.changeType },
        )
        assertEquals("Wrote Kotlin services", variant.plan.experiences.single().achievements.single().evidence.single().text)
        assertEquals(listOf("ach-kotlin"), variant.resume.experiences.single().achievements.map { it.elementId })
    }

    @Test
    fun `rejects a plan that cites evidence outside the confirmed snapshot`() {
        val useCase = useCase {
            TailoringPlan(
                summary = TailoredText(
                    text = "Led a platform team of 9",
                    evidence = listOf(EvidenceRef(EvidenceKind.RESUME_ELEMENT, "ach-rejected")),
                )
            )
        }
        stubDependencies()

        assertThrows<InvalidTailoringPlanException> { useCase.execute(profileId, vacancyId) }
        verify(exactly = 0) { variants.save(any()) }
    }

    @Test
    fun `records an idempotent review approval for the owning profile`() {
        var saved: ResumeVariantEntity? = null
        val useCase = useCase { TailoringPlan() }
        stubDependencies()
        every { variants.save(any()) } answers {
            firstArg<ResumeVariantEntity>().apply { version = 7 }.also { saved = it }
        }
        val created = useCase.execute(profileId, vacancyId)
        every { variants.findByVariantId(created.variantId) } answers { saved }

        val first = useCase.approveReview(profileId, created.variantId)
        val second = useCase.approveReview(profileId, created.variantId)

        assertEquals(Instant.now(clock), first.reviewedAt)
        assertEquals(first.reviewedAt, second.reviewedAt)
    }

    private fun useCase(tailor: (TailoringRequest) -> TailoringPlan) = TailorResumeUseCase(
        profiles = profiles,
        vacancies = vacancies,
        analyzeVacancy = analyzeVacancy,
        resumeImports = resumeImports,
        variants = variants,
        tailor = object : ResumeTailor {
            override fun tailor(request: TailoringRequest) = tailor(request)
        },
        planValidator = TailoringPlanValidator(),
        planBuilder = TailoringPlanBuilder(),
        resumeBuilder = TailoredResumeBuilder(),
        resumeValidator = StructuredResumeValidator(),
        clock = clock,
    )

    private fun stubDependencies() {
        every { profiles.findById(profileId) } returns Optional.of(
            CandidateProfileEntity(profileId, profile(), Instant.EPOCH, Instant.EPOCH)
        )
        every { vacancies.get(vacancyId) } returns vacancy()
        every { analyzeVacancy.get(profileId, vacancyId) } returns AnalysisResult(analysis(), match())
        every { resumeImports.latestConfirmedOrNull(profileId) } returns confirmedImport()
        every { variants.save(any()) } answers { firstArg<ResumeVariantEntity>().apply { version = 7 } }
    }

    private fun reuse(elementId: String, text: String) = TailoredText(
        text = text,
        evidence = listOf(EvidenceRef(EvidenceKind.RESUME_ELEMENT, elementId)),
        sourceElementId = elementId,
    )

    private fun profile() = CandidateProfile(
        profileId,
        GeneralInfo("Test Candidate"),
        facts = listOf(CandidateFact("fact-verified", FactType.SKILL, "Built Kotlin services", true)),
    )

    private fun vacancy() = Vacancy(
        vacancyId, VacancySource.MANUAL, null, null, "Acme", "Backend Engineer",
        "Kotlin required, Kafka preferred", null, null, null, null, null, null, Instant.EPOCH,
    )

    private fun analysis() = VacancyAnalysis(
        role = "Backend Engineer",
        seniority = "Senior",
        requiredSkills = listOf("Kotlin", "Kafka"),
    )

    private fun match() = MatchResult(
        score = 60,
        recommendation = Recommendation.MAYBE,
        reasoningSummary = "Kotlin is covered, Kafka is not.",
        requirementEvidenceMatrix = listOf(
            RequirementEvidenceRow(
                requirement = "Kotlin",
                importance = RequirementImportance.HARD_REQUIREMENT,
                sources = listOf(RequirementSource.REQUIRED_SKILL),
                status = RequirementStatus.MATCHED,
            ),
            RequirementEvidenceRow(
                requirement = "Kafka",
                importance = RequirementImportance.HARD_REQUIREMENT,
                sources = listOf(RequirementSource.REQUIRED_SKILL),
                status = RequirementStatus.MISSING,
            ),
        ),
    )

    private fun confirmedImport() = ResumeImportVersion(
        importId = importId,
        version = 4,
        fileName = "resume.pdf",
        sourceSha256 = "0".repeat(64),
        pageCount = 1,
        extractedText = "Backend engineer",
        textBlocks = emptyList(),
        extractionMethod = ResumeExtractionMethod.TEXT_LAYER,
        warnings = emptyList(),
        structuredResume = StructuredResume(
            summary = ResumeTextElement("summary-1", "Backend engineer", confirmed()),
            experiences = listOf(
                ResumeExperience(
                    elementId = "exp-1",
                    company = "Acme",
                    role = "Backend Engineer",
                    achievements = listOf(
                        ResumeTextElement("ach-kotlin", "Wrote Kotlin services", confirmed()),
                        ResumeTextElement("ach-office", "Organised the office move", confirmed()),
                        ResumeTextElement("ach-rejected", "Managed a team of 9", rejected()),
                    ),
                    metadata = confirmed(),
                )
            ),
            skills = listOf(ResumeSkill("skill-kotlin", "Kotlin", metadata = confirmed())),
        ),
        status = ResumeImportStatus.CONFIRMED,
        createdAt = Instant.EPOCH,
        confirmedAt = Instant.EPOCH,
    )

    private fun confirmed() = ResumeElementMetadata(reviewStatus = ResumeReviewStatus.CONFIRMED)
    private fun rejected() = ResumeElementMetadata(reviewStatus = ResumeReviewStatus.REJECTED)
}
