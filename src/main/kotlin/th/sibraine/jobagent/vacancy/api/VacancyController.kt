package th.sibraine.jobagent.vacancy.api

import th.sibraine.jobagent.candidate.application.CandidateProfileService
import th.sibraine.jobagent.matching.application.AnalysisResult
import th.sibraine.jobagent.matching.application.AnalyzeVacancyUseCase
import th.sibraine.jobagent.vacancy.application.VacancyService
import th.sibraine.jobagent.vacancy.domain.*
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

data class CreateVacancyRequest(
    val source: VacancySource = VacancySource.MANUAL,
    val externalId: String? = null,
    val url: String? = null,
    @field:NotBlank val company: String,
    @field:NotBlank val title: String,
    @field:NotBlank val description: String,
    val location: String? = null,
    val employmentType: EmploymentType? = null,
    val salaryFrom: BigDecimal? = null,
    val salaryTo: BigDecimal? = null,
    val salaryCurrency: String? = null,
    val publishedAt: Instant? = null,
)

@RestController
@RequestMapping("/api/v1/vacancies")
class VacancyController(
    private val vacancies: VacancyService,
    private val analyzeVacancy: AnalyzeVacancyUseCase,
    private val profiles: CandidateProfileService,
) {
    @GetMapping
    fun list(): List<Vacancy> = vacancies.list()

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@Valid @RequestBody request: CreateVacancyRequest): Vacancy = vacancies.create(
        Vacancy(
            UUID(0, 0), request.source, request.externalId, request.url, request.company, request.title,
            request.description, request.location, request.employmentType, request.salaryFrom, request.salaryTo,
            request.salaryCurrency, request.publishedAt, Instant.EPOCH,
        )
    )

    @GetMapping("/{id}") fun get(@PathVariable id: UUID): Vacancy = vacancies.get(id)

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@PathVariable id: UUID) = vacancies.delete(id)

    @PostMapping("/{id}/analyze")
    fun analyze(@PathVariable id: UUID): AnalysisResult = analyzeVacancy.execute(profiles.profileId, id)

    @GetMapping("/{id}/analysis")
    fun getAnalysis(@PathVariable id: UUID): AnalysisResult = analyzeVacancy.get(profiles.profileId, id)
}
