package th.sibraine.jobagent.matching.application

import th.sibraine.jobagent.candidate.infrastructure.CandidateProfileJpaRepository
import th.sibraine.jobagent.matching.domain.*
import th.sibraine.jobagent.matching.infrastructure.*
import th.sibraine.jobagent.shared.NotFoundException
import th.sibraine.jobagent.vacancy.infrastructure.VacancyJpaRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.util.UUID

data class AnalysisResult(val analysis: VacancyAnalysis, val match: MatchResult)

@Service
class AnalyzeVacancyUseCase(
    private val profiles: CandidateProfileJpaRepository,
    private val vacancies: VacancyJpaRepository,
    private val analyses: VacancyAnalysisJpaRepository,
    private val matches: MatchResultJpaRepository,
    private val analyzer: VacancyAnalyzer,
    private val matcher: CandidateMatcher,
    private val validator: MatchResultValidator,
    private val matrixBuilder: RequirementEvidenceMatrixBuilder,
    private val clock: Clock,
) {
    @Transactional
    fun execute(candidateProfileId: UUID, vacancyId: UUID): AnalysisResult {
        val profile = profiles.findById(candidateProfileId).orElseThrow {
            NotFoundException("CANDIDATE_PROFILE_NOT_FOUND", "Candidate profile not found")
        }.profile
        val vacancy = vacancies.findById(vacancyId).orElseThrow {
            NotFoundException("VACANCY_NOT_FOUND", "Vacancy not found")
        }.toDomain()

        val analysis = analyzer.analyze(vacancy)
        val now = Instant.now(clock)
        analyses.findByVacancyId(vacancyId)?.apply {
            this.analysis = analysis
            createdAt = now
        } ?: analyses.save(VacancyAnalysisEntity(UUID.randomUUID(), vacancyId, analysis, now))

        val rawMatch = matcher.match(profile, vacancy, analysis)
        validator.validate(profile, rawMatch)
        val match = rawMatch.copy(
            requirementEvidenceMatrix = matrixBuilder.build(profile, analysis, rawMatch),
        )
        matches.findByCandidateProfileIdAndVacancyId(candidateProfileId, vacancyId)?.apply {
            result = match
            createdAt = now
        } ?: matches.save(MatchResultEntity(UUID.randomUUID(), candidateProfileId, vacancyId, match, now))
        return AnalysisResult(analysis, match)
    }

    @Transactional(readOnly = true)
    fun get(candidateProfileId: UUID, vacancyId: UUID): AnalysisResult {
        val analysis = analyses.findByVacancyId(vacancyId)?.analysis ?: throw NotFoundException(
            "VACANCY_ANALYSIS_NOT_FOUND", "Vacancy analysis not found"
        )
        val match = matches.findByCandidateProfileIdAndVacancyId(candidateProfileId, vacancyId)?.result ?: throw NotFoundException(
            "MATCH_RESULT_NOT_FOUND", "Match result not found"
        )
        return AnalysisResult(analysis, match)
    }
}
