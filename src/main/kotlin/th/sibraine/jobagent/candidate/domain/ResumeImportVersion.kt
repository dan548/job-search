package th.sibraine.jobagent.candidate.domain

import java.time.Instant
import java.util.UUID

enum class ResumeImportStatus { PREVIEW, CONFIRMED }

data class ResumeImportVersion(
    val importId: UUID,
    val version: Long,
    val fileName: String,
    val sourceSha256: String,
    val pageCount: Int,
    val extractedText: String,
    val textBlocks: List<ResumeTextBlock>,
    val extractionMethod: ResumeExtractionMethod,
    val warnings: List<String>,
    val structuredResume: StructuredResume,
    val status: ResumeImportStatus,
    val createdAt: Instant,
    val confirmedAt: Instant? = null,
)
