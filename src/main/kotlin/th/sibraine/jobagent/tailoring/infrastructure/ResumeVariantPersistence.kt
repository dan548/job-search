package th.sibraine.jobagent.tailoring.infrastructure

import th.sibraine.jobagent.candidate.domain.ResumeDiffChange
import th.sibraine.jobagent.candidate.domain.StructuredResume
import th.sibraine.jobagent.tailoring.domain.ResumeVariant
import th.sibraine.jobagent.tailoring.domain.CoverLetter
import th.sibraine.jobagent.tailoring.domain.TailoringPlan
import jakarta.persistence.*
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "resume_variants")
@SequenceGenerator(
    name = "resume_variant_version_generator",
    sequenceName = "resume_variant_version_seq",
    allocationSize = 1,
)
class ResumeVariantEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "resume_variant_version_generator")
    var version: Long = 0,
    @Column(name = "variant_id", nullable = false, unique = true)
    val variantId: UUID,
    @Column(name = "candidate_profile_id", nullable = false)
    val candidateProfileId: UUID,
    @Column(name = "vacancy_id", nullable = false)
    val vacancyId: UUID,
    @Column(name = "base_import_id")
    val baseImportId: UUID?,
    @Column(name = "base_import_version")
    val baseImportVersion: Long?,
    @Column(name = "template_id", nullable = false, length = 100)
    val templateId: String,
    @Column(name = "template_version", nullable = false)
    val templateVersion: Int,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    val plan: TailoringPlan,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    val resume: StructuredResume,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    val diff: List<ResumeDiffChange>,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant,
    @Column(name = "reviewed_at")
    var reviewedAt: Instant? = null,
    @Column(name = "cover_letter_text", columnDefinition = "text")
    var coverLetterText: String? = null,
    @Column(name = "cover_letter_generated_at")
    var coverLetterGeneratedAt: Instant? = null,
) {
    fun toDomain() = ResumeVariant(
        variantId = variantId,
        version = version,
        vacancyId = vacancyId,
        candidateProfileId = candidateProfileId,
        baseImportId = baseImportId,
        baseImportVersion = baseImportVersion,
        templateId = templateId,
        templateVersion = templateVersion,
        plan = plan,
        resume = resume,
        diff = diff,
        createdAt = createdAt,
        reviewedAt = reviewedAt,
        coverLetter = coverLetterText?.let { text ->
            CoverLetter(text, requireNotNull(coverLetterGeneratedAt) { "Cover letter timestamp is missing" })
        },
    )
}

interface ResumeVariantJpaRepository : JpaRepository<ResumeVariantEntity, Long> {
    fun findByVariantId(variantId: UUID): ResumeVariantEntity?
    fun findFirstByCandidateProfileIdAndVacancyIdOrderByVersionDesc(
        candidateProfileId: UUID,
        vacancyId: UUID,
    ): ResumeVariantEntity?
}
