package th.sibraine.jobagent.candidate.application

import th.sibraine.jobagent.candidate.domain.*
import th.sibraine.jobagent.candidate.infrastructure.ResumeImportEntity
import th.sibraine.jobagent.candidate.infrastructure.ResumeImportJpaRepository
import th.sibraine.jobagent.shared.ConflictException
import th.sibraine.jobagent.shared.NotFoundException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.util.UUID

@Service
class ResumeImportService(
    private val parser: ResumeDocumentParser,
    private val decomposer: ResumeDecomposer,
    private val imports: ResumeImportJpaRepository,
    private val validator: StructuredResumeValidator,
    private val profiles: CandidateProfileService,
    private val clock: Clock,
) {
    @Transactional
    fun createPreview(fileName: String, content: ByteArray): ResumeImportVersion {
        val safeFileName = safeFileName(fileName)
        val parsed = parser.parse(ResumeDocument(safeFileName, content))
        val structuredResume = decomposer.decompose(parsed)
        validator.validate(structuredResume)
        return imports.save(
            ResumeImportEntity(
                importId = UUID.randomUUID(),
                candidateProfileId = profiles.profileId,
                fileName = safeFileName,
                sourceSha256 = sha256(content),
                sourcePdf = content,
                pageCount = parsed.pageCount,
                extractedText = parsed.extractedText,
                textBlocks = parsed.textBlocks,
                extractionMethod = parsed.extractionMethod,
                warnings = parsed.warnings,
                structuredResume = structuredResume,
                status = ResumeImportStatus.PREVIEW,
                createdAt = Instant.now(clock),
            )
        ).toDomain()
    }

    @Transactional(readOnly = true)
    fun get(importId: UUID): ResumeImportVersion = find(importId).toDomain()

    @Transactional(readOnly = true)
    fun diff(importId: UUID): ResumeImportDiff {
        val target = find(importId)
        val profileId = target.candidateProfileId
        val base = if (target.status == ResumeImportStatus.CONFIRMED) {
            imports.findFirstByCandidateProfileIdAndStatusAndVersionLessThanOrderByVersionDesc(
                profileId,
                ResumeImportStatus.CONFIRMED,
                target.version,
            )
        } else {
            imports.findFirstByCandidateProfileIdAndStatusOrderByVersionDesc(
                profileId,
                ResumeImportStatus.CONFIRMED,
            )
        }
        return StructuredResumeDiffBuilder().build(
            base?.version,
            base?.structuredResume,
            target.version,
            target.structuredResume,
        )
    }

    @Transactional
    fun confirm(
        importId: UUID,
        structuredResume: StructuredResume,
        mode: ResumeConfirmationMode = ResumeConfirmationMode.REPLACE,
    ): ResumeImportVersion {
        validator.validate(structuredResume, requireReviewed = true)
        val entity = find(importId)
        if (entity.status == ResumeImportStatus.CONFIRMED) {
            val isSameConfirmation = entity.structuredResume == structuredResume ||
                mode == ResumeConfirmationMode.ENRICH &&
                StructuredResumeMerger().enrich(entity.structuredResume, structuredResume) == entity.structuredResume
            if (isSameConfirmation) {
                profiles.syncFromResume(entity.candidateProfileId, entity.structuredResume)
                return entity.toDomain()
            }
            throw ConflictException("RESUME_IMPORT_ALREADY_CONFIRMED", "Confirmed resume import cannot be changed")
        }
        entity.structuredResume = when (mode) {
            ResumeConfirmationMode.REPLACE -> structuredResume
            ResumeConfirmationMode.ENRICH -> imports
                .findFirstByCandidateProfileIdAndStatusOrderByVersionDesc(
                    entity.candidateProfileId,
                    ResumeImportStatus.CONFIRMED,
                )
                ?.structuredResume
                ?.let { StructuredResumeMerger().enrich(it, structuredResume) }
                ?: structuredResume
        }
        entity.status = ResumeImportStatus.CONFIRMED
        entity.confirmedAt = Instant.now(clock)
        profiles.syncFromResume(entity.candidateProfileId, entity.structuredResume)
        return entity.toDomain()
    }

    @Transactional(readOnly = true)
    fun latestConfirmed(profileId: UUID = profiles.profileId): ResumeImportVersion = imports
        .findFirstByCandidateProfileIdAndStatusOrderByVersionDesc(
            profileId,
            ResumeImportStatus.CONFIRMED,
        )
        ?.toDomain()
        ?: throw NotFoundException("CONFIRMED_RESUME_NOT_FOUND", "Confirmed structured resume not found")

    @Transactional(readOnly = true)
    fun latestConfirmedOrNull(profileId: UUID = profiles.profileId): ResumeImportVersion? = imports
        .findFirstByCandidateProfileIdAndStatusOrderByVersionDesc(profileId, ResumeImportStatus.CONFIRMED)
        ?.toDomain()

    private fun find(importId: UUID): ResumeImportEntity = imports.findByImportId(importId)
        ?: throw NotFoundException("RESUME_IMPORT_NOT_FOUND", "Resume import not found")

    private fun safeFileName(fileName: String): String = fileName
        .substringAfterLast('/')
        .substringAfterLast('\\')
        .take(255)
        .ifBlank { "resume.pdf" }

    private fun sha256(content: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(content)
        .joinToString("") { "%02x".format(it) }
}
