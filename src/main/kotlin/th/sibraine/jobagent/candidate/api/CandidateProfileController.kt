package th.sibraine.jobagent.candidate.api

import th.sibraine.jobagent.candidate.application.CandidateProfileService
import th.sibraine.jobagent.candidate.application.ParseResumeUseCase
import th.sibraine.jobagent.candidate.domain.*
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*
import org.springframework.http.MediaType
import org.springframework.web.multipart.MultipartFile
import java.util.UUID

data class PutCandidateProfileRequest(
    @field:Valid val generalInfo: GeneralInfoRequest,
    val roles: List<String> = emptyList(),
    val projects: List<Project> = emptyList(),
    val skills: Set<String> = emptySet(),
    val preferences: JobPreferences = JobPreferences(),
    val constraints: List<String> = emptyList(),
    @field:Valid val facts: List<CandidateFactRequest> = emptyList(),
    val label: String? = null,
    val contacts: List<ResumeContact> = emptyList(),
    val experiences: List<ResumeExperience> = emptyList(),
)

data class GeneralInfoRequest(@field:NotBlank val displayName: String, val headline: String? = null)
data class CandidateFactRequest(
    @field:NotBlank val factId: String,
    val type: FactType,
    @field:NotBlank val text: String,
    val verified: Boolean,
)
data class SaveCandidateFactRequest(val type: FactType = FactType.OTHER, @field:NotBlank val text: String)

data class CreateIdentityRequest(@field:NotBlank val label: String, @field:NotBlank val displayName: String)
data class UpdateProfileBasicsRequest(
    @field:NotBlank val label: String,
    @field:NotBlank val displayName: String,
    val headline: String? = null,
    val skills: Set<String> = emptySet(),
)
data class SaveContactRequest(
    val type: ResumeContactType,
    @field:NotBlank val value: String,
    val label: String? = null,
)
data class SaveExperienceRequest(
    @field:NotBlank val company: String,
    @field:NotBlank val role: String,
    val location: String? = null,
    val startDate: ResumeDate? = null,
    val endDate: ResumeDate? = null,
    val current: Boolean = false,
    val description: String? = null,
    val technologies: List<String> = emptyList(),
)

@RestController
@RequestMapping("/api/v1/candidate-profile")
class CandidateProfileController(
    private val service: CandidateProfileService,
    private val parseResume: ParseResumeUseCase,
) {
    @PutMapping
    fun put(@Valid @RequestBody request: PutCandidateProfileRequest): CandidateProfile = service.put(
        CandidateProfile(
            id = UUID(0, 0),
            generalInfo = GeneralInfo(request.generalInfo.displayName, request.generalInfo.headline),
            roles = request.roles,
            projects = request.projects,
            skills = request.skills,
            preferences = request.preferences,
            constraints = request.constraints,
            facts = request.facts.map { CandidateFact(it.factId, it.type, it.text, it.verified) },
            label = request.label,
            contacts = request.contacts,
            experiences = request.experiences,
        )
    )

    @GetMapping fun get(): CandidateProfile = service.get()

    @PostMapping("/facts")
    @ResponseStatus(HttpStatus.CREATED)
    fun addFact(@Valid @RequestBody request: SaveCandidateFactRequest): CandidateProfile =
        service.addConfirmedFact(request.type, request.text)

    @PutMapping("/details")
    fun updateDetails(@Valid @RequestBody request: UpdateProfileBasicsRequest): CandidateProfile {
        val current = service.get()
        return service.put(
            current.copy(
                label = request.label.trim(),
                generalInfo = GeneralInfo(request.displayName.trim(), request.headline?.trim()?.ifBlank { null }),
                skills = request.skills.map(String::trim).filter(String::isNotEmpty).toSet(),
            )
        )
    }

    @PostMapping("/contacts")
    @ResponseStatus(HttpStatus.CREATED)
    fun addContact(@Valid @RequestBody request: SaveContactRequest): CandidateProfile {
        val current = service.get()
        val contact = ResumeContact(
            elementId = "manual-contact-${UUID.randomUUID()}",
            type = request.type,
            value = request.value.trim(),
            label = request.label?.trim()?.ifBlank { null },
            metadata = confirmedManualMetadata(request.value),
        )
        return service.put(current.copy(contacts = current.contacts + contact))
    }

    @PutMapping("/contacts/{elementId}")
    fun updateContact(
        @PathVariable elementId: String,
        @Valid @RequestBody request: SaveContactRequest,
    ): CandidateProfile {
        val current = service.get()
        return service.put(current.copy(contacts = current.contacts.map {
            if (it.elementId == elementId) ResumeContact(
                elementId = if (it.elementId.startsWith("manual-")) it.elementId else "manual-contact-${UUID.randomUUID()}",
                type = request.type,
                value = request.value.trim(),
                label = request.label?.trim()?.ifBlank { null },
                metadata = confirmedManualMetadata(request.value),
            ) else it
        }))
    }

    @DeleteMapping("/contacts/{elementId}")
    fun deleteContact(@PathVariable elementId: String): CandidateProfile {
        val current = service.get()
        return service.put(current.copy(contacts = current.contacts.filterNot { it.elementId == elementId }))
    }

    @PostMapping("/experiences")
    @ResponseStatus(HttpStatus.CREATED)
    fun addExperience(@Valid @RequestBody request: SaveExperienceRequest): CandidateProfile {
        val current = service.get()
        return service.put(current.copy(experiences = current.experiences + request.toExperience()))
    }

    @PutMapping("/experiences/{elementId}")
    fun updateExperience(
        @PathVariable elementId: String,
        @Valid @RequestBody request: SaveExperienceRequest,
    ): CandidateProfile {
        val current = service.get()
        return service.put(current.copy(experiences = current.experiences.map {
            if (it.elementId == elementId) request.toExperience(
                if (it.elementId.startsWith("manual-")) it.elementId else "manual-experience-${UUID.randomUUID()}",
                it.achievements,
            ) else it
        }))
    }

    @DeleteMapping("/experiences/{elementId}")
    fun deleteExperience(@PathVariable elementId: String): CandidateProfile {
        val current = service.get()
        return service.put(current.copy(experiences = current.experiences.filterNot { it.elementId == elementId }))
    }

    @PostMapping("/import/pdf", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun importPdf(@RequestPart("file") file: MultipartFile): ParsedResume =
        parseResume.execute(file.originalFilename ?: "resume.pdf", file.bytes)

    private fun SaveExperienceRequest.toExperience(
        elementId: String = "manual-experience-${UUID.randomUUID()}",
        achievements: List<ResumeTextElement> = emptyList(),
    ) = ResumeExperience(
        elementId = elementId,
        company = company.trim(),
        role = role.trim(),
        location = location?.trim()?.ifBlank { null },
        startDate = startDate,
        endDate = if (current) null else endDate,
        current = current,
        description = description?.trim()?.ifBlank { null },
        achievements = achievements,
        metadata = confirmedManualMetadata(description?.takeIf(String::isNotBlank) ?: "$role at $company"),
        technologies = technologies.map(String::trim).filter(String::isNotEmpty).distinct(),
    )

    private fun confirmedManualMetadata(source: String) = ResumeElementMetadata(
        provenance = ResumeProvenance(source.trim()),
        confidence = 1.0,
        reviewStatus = ResumeReviewStatus.CONFIRMED,
    )
}

@RestController
@RequestMapping("/api/v1/candidate-identities")
class CandidateIdentityController(private val service: CandidateProfileService) {
    @GetMapping
    fun list() = service.identities()

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@Valid @RequestBody request: CreateIdentityRequest) =
        service.createIdentity(request.label, request.displayName)

    @PostMapping("/{id}/activate")
    fun activate(@PathVariable id: UUID) = service.activate(id)
}
