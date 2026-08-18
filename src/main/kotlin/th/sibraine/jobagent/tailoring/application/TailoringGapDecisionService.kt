package th.sibraine.jobagent.tailoring.application

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import th.sibraine.jobagent.candidate.application.CandidateProfileService
import th.sibraine.jobagent.candidate.domain.FactType
import th.sibraine.jobagent.shared.NotFoundException
import th.sibraine.jobagent.tailoring.domain.TailoringGapDecision
import th.sibraine.jobagent.tailoring.domain.TailoringGapDecisionType
import th.sibraine.jobagent.tailoring.infrastructure.ResumeVariantJpaRepository
import th.sibraine.jobagent.tailoring.infrastructure.TailoringGapDecisionEntity
import th.sibraine.jobagent.tailoring.infrastructure.TailoringGapDecisionJpaRepository
import java.time.Clock
import java.time.Instant
import java.util.UUID

data class SaveTailoringGapDecisionCommand(
    val type: TailoringGapDecisionType,
    val explanation: String? = null,
    val factType: FactType? = null,
    val factText: String? = null,
)

@Service
class TailoringGapDecisionService(
    private val variants: ResumeVariantJpaRepository,
    private val decisions: TailoringGapDecisionJpaRepository,
    private val profiles: CandidateProfileService,
    private val clock: Clock,
) {
    @Transactional
    fun save(
        candidateProfileId: UUID,
        variantId: UUID,
        questionId: String,
        command: SaveTailoringGapDecisionCommand,
    ): TailoringGapDecision {
        val variant = variants.findByVariantId(variantId)
            ?.takeIf { it.candidateProfileId == candidateProfileId }
            ?: throw NotFoundException("RESUME_VARIANT_NOT_FOUND", "Resume variant not found")
        val question = variant.plan.questions.singleOrNull { it.questionId == questionId }
            ?: throw NotFoundException("TAILORING_QUESTION_NOT_FOUND", "Tailoring question not found")
        val groupId = question.groupId.ifBlank {
            variant.plan.gapGroups.singleOrNull { it.title == question.requirement }?.groupId ?: question.questionId
        }

        val factId = if (command.type == TailoringGapDecisionType.CONFIRMED_FACT_ADDED) {
            val factType = requireNotNull(command.factType) { "factType is required when adding a confirmed fact" }
            val factText = command.factText?.trim().orEmpty()
            require(factText.isNotEmpty()) { "factText is required when adding a confirmed fact" }
            profiles.addConfirmedFact(factType, factText).facts
                .last { it.type == factType && it.text == factText && it.verified }
                .factId
        } else {
            require(command.factType == null && command.factText.isNullOrBlank()) {
                "factType and factText are only allowed when adding a confirmed fact"
            }
            null
        }

        val decidedAt = Instant.now(clock)
        val explanation = command.explanation?.trim()?.takeIf(String::isNotEmpty)
            ?: defaultExplanation(command.type, command.factText)
        require(explanation.length <= 2_000) { "Decision explanation must not exceed 2000 characters" }

        val stored = decisions.findById(
            th.sibraine.jobagent.tailoring.infrastructure.TailoringGapDecisionId(
                candidateProfileId,
                variant.vacancyId,
                groupId,
            )
        ).orElse(null)?.apply {
            decisionType = command.type
            this.explanation = explanation
            confirmedFactId = factId
            this.decidedAt = decidedAt
        } ?: TailoringGapDecisionEntity(
            candidateProfileId = candidateProfileId,
            vacancyId = variant.vacancyId,
            groupId = groupId,
            decisionType = command.type,
            explanation = explanation,
            confirmedFactId = factId,
            decidedAt = decidedAt,
        )
        val decision = decisions.save(stored).toDomain()
        variant.plan = variant.plan.copy(
            gapGroups = variant.plan.gapGroups.map {
                if (it.groupId == groupId) it.copy(decision = decision) else it
            },
            questions = variant.plan.questions.map {
                if (it.questionId == questionId) it.copy(groupId = groupId, decision = decision) else it
            },
        )
        return decision
    }

    @Transactional(readOnly = true)
    fun forVacancy(candidateProfileId: UUID, vacancyId: UUID): Map<String, TailoringGapDecision> = decisions
        .findAllByCandidateProfileIdAndVacancyId(candidateProfileId, vacancyId)
        .associate { it.groupId to it.toDomain() }

    private fun defaultExplanation(type: TailoringGapDecisionType, factText: String?): String = when (type) {
        TailoringGapDecisionType.CONFIRMED_FACT_ADDED -> "Добавлен подтверждённый факт: ${factText?.trim()}"
        TailoringGapDecisionType.CANNOT_CONFIRM -> "Подтверждаемого факта сейчас нет."
        TailoringGapDecisionType.NOT_APPLICABLE -> "Требование не относится к этому кандидату или отклику."
        TailoringGapDecisionType.ACCEPT_RISK -> "Риск принят для этого отклика."
    }
}
