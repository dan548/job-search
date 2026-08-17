package th.sibraine.jobagent.candidate.infrastructure

import th.sibraine.jobagent.candidate.domain.CandidateProfile
import jakarta.persistence.*
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "candidate_profiles")
class CandidateProfileEntity(
    @Id val id: UUID,
    @JdbcTypeCode(SqlTypes.JSON) @Column(columnDefinition = "jsonb", nullable = false)
    var profile: CandidateProfile,
    @Column(name = "created_at", nullable = false) val createdAt: Instant,
    @Column(name = "updated_at", nullable = false) var updatedAt: Instant,
    @Column(nullable = false) var active: Boolean = false,
)

interface CandidateProfileJpaRepository : JpaRepository<CandidateProfileEntity, UUID> {
    fun findFirstByActiveTrue(): CandidateProfileEntity?
    fun findAllByOrderByCreatedAtAsc(): List<CandidateProfileEntity>
}
