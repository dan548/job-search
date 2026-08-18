package th.sibraine.jobagent.tailoring.infrastructure

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.IdClass
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import th.sibraine.jobagent.tailoring.domain.TailoringGapDecision
import th.sibraine.jobagent.tailoring.domain.TailoringGapDecisionType
import java.io.Serializable
import java.time.Instant
import java.util.UUID

data class TailoringGapDecisionId(
    var candidateProfileId: UUID = UUID(0, 0),
    var vacancyId: UUID = UUID(0, 0),
    var groupId: String = "",
) : Serializable

@Entity
@Table(name = "tailoring_gap_decisions")
@IdClass(TailoringGapDecisionId::class)
class TailoringGapDecisionEntity(
    @Id
    @Column(name = "candidate_profile_id", nullable = false)
    val candidateProfileId: UUID,
    @Id
    @Column(name = "vacancy_id", nullable = false)
    val vacancyId: UUID,
    @Id
    @Column(name = "group_id", nullable = false, length = 300)
    val groupId: String,
    @Column(name = "decision_type", nullable = false, length = 40)
    @Enumerated(EnumType.STRING)
    var decisionType: TailoringGapDecisionType,
    @Column(nullable = false, columnDefinition = "text")
    var explanation: String,
    @Column(name = "confirmed_fact_id", length = 200)
    var confirmedFactId: String? = null,
    @Column(name = "decided_at", nullable = false)
    var decidedAt: Instant,
) {
    fun toDomain() = TailoringGapDecision(decisionType, explanation, confirmedFactId, decidedAt)
}

interface TailoringGapDecisionJpaRepository : JpaRepository<TailoringGapDecisionEntity, TailoringGapDecisionId> {
    fun findAllByCandidateProfileIdAndVacancyId(candidateProfileId: UUID, vacancyId: UUID): List<TailoringGapDecisionEntity>
}
