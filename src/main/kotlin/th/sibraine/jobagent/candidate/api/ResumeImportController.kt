package th.sibraine.jobagent.candidate.api

import th.sibraine.jobagent.candidate.application.ResumeImportService
import th.sibraine.jobagent.candidate.domain.ResumeImportVersion
import th.sibraine.jobagent.candidate.domain.ResumeImportDiff
import th.sibraine.jobagent.candidate.domain.ResumeConfirmationMode
import th.sibraine.jobagent.candidate.domain.StructuredResume
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile
import java.util.UUID

data class ConfirmResumeImportRequest(
    val structuredResume: StructuredResume,
    val mode: ResumeConfirmationMode = ResumeConfirmationMode.REPLACE,
)

@RestController
@RequestMapping("/api/v1/candidate-profile/resume-imports")
class ResumeImportController(private val service: ResumeImportService) {
    @PostMapping(consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    @ResponseStatus(HttpStatus.CREATED)
    fun createPreview(@RequestPart("file") file: MultipartFile): ResumeImportVersion =
        service.createPreview(file.originalFilename ?: "resume.pdf", file.bytes)

    @GetMapping("/{importId}")
    fun get(@PathVariable importId: UUID): ResumeImportVersion = service.get(importId)

    @GetMapping("/{importId}/diff")
    fun diff(@PathVariable importId: UUID): ResumeImportDiff = service.diff(importId)

    @PostMapping("/{importId}/confirm")
    fun confirm(
        @PathVariable importId: UUID,
        @RequestBody request: ConfirmResumeImportRequest,
    ): ResumeImportVersion = service.confirm(importId, request.structuredResume, request.mode)

    @GetMapping("/confirmed/latest")
    fun latestConfirmed(): ResumeImportVersion = service.latestConfirmed()
}
