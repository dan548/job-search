package th.sibraine.jobagent.candidate.infrastructure

import th.sibraine.jobagent.candidate.domain.ResumeImportStatus
import th.sibraine.jobagent.candidate.domain.ResumeImportVersion
import th.sibraine.jobagent.candidate.domain.ResumeExtractionMethod
import th.sibraine.jobagent.candidate.domain.ResumeTextBlock
import th.sibraine.jobagent.candidate.domain.StructuredResume
import jakarta.persistence.*
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "resume_imports")
@SequenceGenerator(
    name = "resume_import_version_generator",
    sequenceName = "resume_import_version_seq",
    allocationSize = 1,
)
class ResumeImportEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "resume_import_version_generator")
    var version: Long = 0,
    @Column(name = "import_id", nullable = false, unique = true)
    val importId: UUID,
    @Column(name = "candidate_profile_id", nullable = false)
    val candidateProfileId: UUID,
    @Column(name = "file_name", nullable = false, length = 255)
    val fileName: String,
    @Column(name = "source_sha256", nullable = false, length = 64)
    val sourceSha256: String,
    @Column(name = "source_pdf", nullable = false, columnDefinition = "bytea")
    val sourcePdf: ByteArray,
    @Column(name = "page_count", nullable = false)
    val pageCount: Int,
    @Column(name = "extracted_text", nullable = false, columnDefinition = "text")
    val extractedText: String,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "text_blocks", nullable = false, columnDefinition = "jsonb")
    val textBlocks: List<ResumeTextBlock> = emptyList(),
    @Enumerated(EnumType.STRING)
    @Column(name = "extraction_method", nullable = false, length = 20)
    val extractionMethod: ResumeExtractionMethod = ResumeExtractionMethod.TEXT_LAYER,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    val warnings: List<String>,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "structured_resume", nullable = false, columnDefinition = "jsonb")
    var structuredResume: StructuredResume,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: ResumeImportStatus,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant,
    @Column(name = "confirmed_at")
    var confirmedAt: Instant? = null,
) {
    fun toDomain() = ResumeImportVersion(
        importId = importId,
        version = version,
        fileName = fileName,
        sourceSha256 = sourceSha256,
        pageCount = pageCount,
        extractedText = extractedText,
        textBlocks = textBlocks,
        extractionMethod = extractionMethod,
        warnings = warnings,
        structuredResume = structuredResume,
        status = status,
        createdAt = createdAt,
        confirmedAt = confirmedAt,
    )
}

interface ResumeImportJpaRepository : JpaRepository<ResumeImportEntity, Long> {
    fun findByImportId(importId: UUID): ResumeImportEntity?
    fun findFirstByCandidateProfileIdAndStatusOrderByVersionDesc(
        candidateProfileId: UUID,
        status: ResumeImportStatus,
    ): ResumeImportEntity?
    fun findFirstByCandidateProfileIdAndStatusAndVersionLessThanOrderByVersionDesc(
        candidateProfileId: UUID,
        status: ResumeImportStatus,
        version: Long,
    ): ResumeImportEntity?
}
