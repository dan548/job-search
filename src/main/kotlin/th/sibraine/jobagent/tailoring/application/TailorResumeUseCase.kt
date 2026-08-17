package th.sibraine.jobagent.tailoring.application

import th.sibraine.jobagent.candidate.application.ResumeImportService
import th.sibraine.jobagent.candidate.domain.StructuredResumeDiffBuilder
import th.sibraine.jobagent.candidate.domain.StructuredResumeValidator
import th.sibraine.jobagent.candidate.domain.confirmedOnly
import th.sibraine.jobagent.candidate.infrastructure.CandidateProfileJpaRepository
import th.sibraine.jobagent.matching.application.AnalyzeVacancyUseCase
import th.sibraine.jobagent.shared.NotFoundException
import th.sibraine.jobagent.tailoring.domain.*
import th.sibraine.jobagent.tailoring.infrastructure.ResumeVariantEntity
import th.sibraine.jobagent.tailoring.infrastructure.ResumeVariantJpaRepository
import th.sibraine.jobagent.vacancy.application.VacancyService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.util.UUID

@Service
class TailorResumeUseCase(
    private val profiles: CandidateProfileJpaRepository,
    private val vacancies: VacancyService,
    private val analyzeVacancy: AnalyzeVacancyUseCase,
    private val resumeImports: ResumeImportService,
    private val variants: ResumeVariantJpaRepository,
    private val tailor: ResumeTailor,
    private val planValidator: TailoringPlanValidator,
    private val planBuilder: TailoringPlanBuilder,
    private val resumeBuilder: TailoredResumeBuilder,
    private val resumeValidator: StructuredResumeValidator,
    private val clock: Clock,
) {
    private val diffBuilder = StructuredResumeDiffBuilder()
    private val assembler = CanonicalResumeAssembler()

    @Transactional(readOnly = true)
    fun selection(candidateProfileId: UUID, vacancyId: UUID): ResumeContentSelection {
        val vacancy = vacancies.get(vacancyId)
        val profile = profiles.findById(candidateProfileId).orElseThrow {
            NotFoundException("CANDIDATE_PROFILE_NOT_FOUND", "Candidate profile not found")
        }.profile
        val imported = resumeImports.latestConfirmedOrNull(candidateProfileId)?.structuredResume
        return assembler.options(assembler.assemble(profile, imported), "${vacancy.title}\n${vacancy.description}")
    }

    @Transactional
    fun execute(
        candidateProfileId: UUID,
        vacancyId: UUID,
        selection: ResumeContentSelectionRequest? = null,
    ): ResumeVariant {
        val profile = profiles.findById(candidateProfileId).orElseThrow {
            NotFoundException("CANDIDATE_PROFILE_NOT_FOUND", "Candidate profile not found")
        }.profile
        val vacancy = vacancies.get(vacancyId)
        val analysis = analyzeVacancy.get(candidateProfileId, vacancyId)
        val base = resumeImports.latestConfirmedOrNull(candidateProfileId)
        val canonical = assembler.assemble(profile, base?.structuredResume)
        val confirmed = assembler.select(canonical, selection)

        val tailored = tailor.tailor(
            TailoringRequest(vacancy, analysis.analysis, analysis.match, profile, confirmed)
        )
        val draft = if (selection == null) tailored else ensureSelected(tailored, confirmed)
        planValidator.validate(draft, confirmed, profile)

        val resume = resumeBuilder.build(confirmed, profile, draft)
        resumeValidator.validate(resume)
        val plan = planBuilder.build(draft, base?.structuredResume ?: canonical, confirmed, resume, profile, analysis.match)

        return variants.save(
            ResumeVariantEntity(
                variantId = UUID.randomUUID(),
                candidateProfileId = candidateProfileId,
                vacancyId = vacancyId,
                baseImportId = base?.importId,
                baseImportVersion = base?.version,
                templateId = CURRENT_TEMPLATE_ID,
                templateVersion = CURRENT_TEMPLATE_VERSION,
                plan = plan,
                resume = resume,
                diff = diffBuilder.changes(confirmed, resume),
                createdAt = Instant.now(clock),
            )
        ).toDomain()
    }

    @Transactional(readOnly = true)
    fun latest(candidateProfileId: UUID, vacancyId: UUID): ResumeVariant = variants
        .findFirstByCandidateProfileIdAndVacancyIdOrderByVersionDesc(candidateProfileId, vacancyId)
        ?.toDomain()
        ?: throw NotFoundException("RESUME_VARIANT_NOT_FOUND", "Resume variant not found")

    @Transactional(readOnly = true)
    fun get(variantId: UUID): ResumeVariant = variants.findByVariantId(variantId)
        ?.toDomain()
        ?: throw NotFoundException("RESUME_VARIANT_NOT_FOUND", "Resume variant not found")

    private fun ensureSelected(plan: TailoringPlan, resume: th.sibraine.jobagent.candidate.domain.StructuredResume): TailoringPlan {
        val plannedExperiences = plan.experiences.map { it.sourceElementId }.toSet()
        val missingExperiences = resume.experiences.filter { it.elementId !in plannedExperiences }.map { experience ->
            TailoredExperience(
                experience.elementId,
                experience.achievements.map { achievement ->
                    TailoredText(
                        achievement.text,
                        evidence = listOf(EvidenceRef(EvidenceKind.RESUME_ELEMENT, achievement.elementId)),
                        sourceElementId = achievement.elementId,
                    )
                },
            )
        }
        val selectedSkillIds = resume.skills.map { it.elementId }
        return plan.copy(
            experiences = plan.experiences + missingExperiences,
            skillElementIds = (plan.skillElementIds.filter { it in selectedSkillIds } + selectedSkillIds).distinct(),
        )
    }
}
