package th.sibraine.jobagent.matching.infrastructure

import th.sibraine.jobagent.matching.domain.MatchResult
import th.sibraine.jobagent.matching.domain.VacancyAnalysis
import jakarta.persistence.*
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "vacancy_analyses")
class VacancyAnalysisEntity(
    @Id val id: UUID,
    @Column(name = "vacancy_id", nullable = false, unique = true) val vacancyId: UUID,
    @JdbcTypeCode(SqlTypes.JSON) @Column(columnDefinition = "jsonb", nullable = false)
    var analysis: VacancyAnalysis,
    @Column(name = "created_at", nullable = false) var createdAt: Instant,
)

@Entity
@Table(name = "match_results")
class MatchResultEntity(
    @Id val id: UUID,
    @Column(name = "candidate_profile_id", nullable = false) val candidateProfileId: UUID,
    @Column(name = "vacancy_id", nullable = false) val vacancyId: UUID,
    @JdbcTypeCode(SqlTypes.JSON) @Column(columnDefinition = "jsonb", nullable = false)
    var result: MatchResult,
    @Column(name = "created_at", nullable = false) var createdAt: Instant,
)

interface VacancyAnalysisJpaRepository : JpaRepository<VacancyAnalysisEntity, UUID> {
    fun findByVacancyId(vacancyId: UUID): VacancyAnalysisEntity?
}

interface MatchResultJpaRepository : JpaRepository<MatchResultEntity, UUID> {
    fun findByCandidateProfileIdAndVacancyId(candidateProfileId: UUID, vacancyId: UUID): MatchResultEntity?
    fun findFirstByVacancyIdOrderByCreatedAtDesc(vacancyId: UUID): MatchResultEntity?
}
