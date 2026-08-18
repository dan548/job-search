package th.sibraine.jobagent.tailoring.application

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import th.sibraine.jobagent.candidate.application.CandidateProfileService
import th.sibraine.jobagent.candidate.domain.CandidateFact
import th.sibraine.jobagent.candidate.domain.CandidateProfile
import th.sibraine.jobagent.candidate.domain.FactType
import th.sibraine.jobagent.candidate.domain.GeneralInfo
import th.sibraine.jobagent.candidate.domain.StructuredResume
import th.sibraine.jobagent.matching.domain.RequirementImportance
import th.sibraine.jobagent.matching.domain.RequirementStatus
import th.sibraine.jobagent.tailoring.domain.CURRENT_TEMPLATE_ID
import th.sibraine.jobagent.tailoring.domain.CURRENT_TEMPLATE_VERSION
import th.sibraine.jobagent.tailoring.domain.TailoringGapDecisionType
import th.sibraine.jobagent.tailoring.domain.TailoringGapGroup
import th.sibraine.jobagent.tailoring.domain.TailoringPlan
import th.sibraine.jobagent.tailoring.domain.TailoringQuestion
import th.sibraine.jobagent.tailoring.infrastructure.ResumeVariantEntity
import th.sibraine.jobagent.tailoring.infrastructure.ResumeVariantJpaRepository
import th.sibraine.jobagent.tailoring.infrastructure.TailoringGapDecisionEntity
import th.sibraine.jobagent.tailoring.infrastructure.TailoringGapDecisionJpaRepository
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Optional
import java.util.UUID

class TailoringGapDecisionServiceTest {
    private val variants = mockk<ResumeVariantJpaRepository>()
    private val decisions = mockk<TailoringGapDecisionJpaRepository>()
    private val profiles = mockk<CandidateProfileService>()
    private val now = Instant.parse("2026-08-18T08:00:00Z")
    private val service = TailoringGapDecisionService(
        variants,
        decisions,
        profiles,
        Clock.fixed(now, ZoneOffset.UTC),
    )
    private val profileId = UUID.randomUUID()
    private val vacancyId = UUID.randomUUID()
    private val variantId = UUID.randomUUID()

    @Test
    fun `persists a declined gap and annotates the current variant`() {
        val variant = variant()
        every { variants.findByVariantId(variantId) } returns variant
        every { decisions.findById(any()) } returns Optional.empty()
        every { decisions.save(any()) } answers { firstArg() }

        val result = service.save(
            profileId,
            variantId,
            "question-jvm",
            SaveTailoringGapDecisionCommand(TailoringGapDecisionType.CANNOT_CONFIRM, "Нет коммерческого опыта"),
        )

        assertEquals(TailoringGapDecisionType.CANNOT_CONFIRM, result.type)
        assertEquals("Нет коммерческого опыта", result.explanation)
        assertEquals(now, result.decidedAt)
        assertEquals(result, variant.plan.questions.single().decision)
        assertEquals(result, variant.plan.gapGroups.single().decision)
        verify(exactly = 0) { profiles.addConfirmedFact(any(), any()) }
    }

    @Test
    fun `adds a verified fact and keeps its id with the decision`() {
        val variant = variant()
        val fact = CandidateFact("manual-fact-kotlin", FactType.EXPERIENCE, "Разрабатывал сервисы на Kotlin", true)
        every { variants.findByVariantId(variantId) } returns variant
        every { decisions.findById(any()) } returns Optional.empty()
        every { decisions.save(any()) } answers { firstArg<TailoringGapDecisionEntity>() }
        every { profiles.addConfirmedFact(FactType.EXPERIENCE, fact.text) } returns CandidateProfile(
            profileId,
            GeneralInfo("Candidate"),
            facts = listOf(fact),
        )

        val result = service.save(
            profileId,
            variantId,
            "question-jvm",
            SaveTailoringGapDecisionCommand(
                TailoringGapDecisionType.CONFIRMED_FACT_ADDED,
                factType = FactType.EXPERIENCE,
                factText = fact.text,
            ),
        )

        assertEquals(fact.factId, result.confirmedFactId)
        assertEquals("Добавлен подтверждённый факт: ${fact.text}", result.explanation)
        verify(exactly = 1) { profiles.addConfirmedFact(FactType.EXPERIENCE, fact.text) }
    }

    private fun variant() = ResumeVariantEntity(
        variantId = variantId,
        candidateProfileId = profileId,
        vacancyId = vacancyId,
        baseImportId = null,
        baseImportVersion = null,
        templateId = CURRENT_TEMPLATE_ID,
        templateVersion = CURRENT_TEMPLATE_VERSION,
        plan = TailoringPlan(
            gapGroups = listOf(
                TailoringGapGroup(
                    groupId = "jvm",
                    title = "Java и Kotlin",
                    importance = RequirementImportance.HARD_REQUIREMENT,
                    status = RequirementStatus.MISSING,
                )
            ),
            questions = listOf(
                TailoringQuestion(
                    questionId = "question-jvm",
                    groupId = "jvm",
                    question = "Есть ли подтверждаемый опыт?",
                    requirement = "Java и Kotlin",
                    importance = RequirementImportance.HARD_REQUIREMENT,
                )
            ),
        ),
        resume = StructuredResume(),
        diff = emptyList(),
        createdAt = now,
    )
}
