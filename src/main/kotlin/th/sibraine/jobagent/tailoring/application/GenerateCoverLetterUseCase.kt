package th.sibraine.jobagent.tailoring.application

import th.sibraine.jobagent.shared.NotFoundException
import th.sibraine.jobagent.tailoring.domain.CoverLetter
import th.sibraine.jobagent.tailoring.domain.CoverLetterGenerator
import th.sibraine.jobagent.tailoring.domain.CoverLetterRequest
import th.sibraine.jobagent.tailoring.infrastructure.ResumeVariantJpaRepository
import th.sibraine.jobagent.vacancy.application.VacancyService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.util.UUID

@Service
class GenerateCoverLetterUseCase(
    private val variants: ResumeVariantJpaRepository,
    private val vacancies: VacancyService,
    private val generator: CoverLetterGenerator,
    private val clock: Clock,
) {
    @Transactional
    fun execute(candidateProfileId: UUID, variantId: UUID): CoverLetter {
        val variant = variants.findByVariantId(variantId)
            ?.takeIf { it.candidateProfileId == candidateProfileId }
            ?: throw NotFoundException("RESUME_VARIANT_NOT_FOUND", "Resume variant not found")
        val text = generator.generate(CoverLetterRequest(vacancies.get(variant.vacancyId), variant.resume)).trim()
        require(text.isNotBlank()) { "Generated cover letter must not be blank" }
        val generatedAt = Instant.now(clock)
        variant.coverLetterText = text
        variant.coverLetterGeneratedAt = generatedAt
        return CoverLetter(text, generatedAt)
    }
}
