package th.sibraine.jobagent.matching.application

import th.sibraine.jobagent.candidate.domain.*
import th.sibraine.jobagent.candidate.infrastructure.*
import th.sibraine.jobagent.matching.domain.*
import th.sibraine.jobagent.matching.infrastructure.*
import th.sibraine.jobagent.vacancy.domain.*
import th.sibraine.jobagent.vacancy.infrastructure.*
import io.mockk.*
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Optional
import java.util.UUID

class AnalyzeVacancyUseCaseTest {
    @Test
    fun `loads analyzes persists validates matches and persists in order`() {
        val profileId = UUID.randomUUID()
        val vacancyId = UUID.randomUUID()
        val profile = CandidateProfile(profileId, GeneralInfo("Candidate"))
        val vacancy = Vacancy(vacancyId, VacancySource.MANUAL, null, null, "Acme", "Engineer", "Text",
            null, null, null, null, null, null, Instant.EPOCH)
        val analysis = VacancyAnalysis("Engineer", null)
        val result = MatchResult(100, Recommendation.PRIORITY, reasoningSummary = "match")
        val profiles = mockk<CandidateProfileJpaRepository>()
        val vacancies = mockk<VacancyJpaRepository>()
        val analyses = mockk<VacancyAnalysisJpaRepository>()
        val matches = mockk<MatchResultJpaRepository>()
        val analyzer = mockk<VacancyAnalyzer>()
        val matcher = mockk<CandidateMatcher>()
        val validator = mockk<MatchResultValidator>()
        val matrixBuilder = mockk<RequirementEvidenceMatrixBuilder>()

        every { profiles.findById(profileId) } returns Optional.of(CandidateProfileEntity(profileId, profile, Instant.EPOCH, Instant.EPOCH))
        every { vacancies.findById(vacancyId) } returns Optional.of(VacancyEntity.from(vacancy))
        every { analyzer.analyze(vacancy) } returns analysis
        every { analyses.findByVacancyId(vacancyId) } returns null
        every { analyses.save(any()) } answers { firstArg() }
        every { matcher.match(profile, vacancy, analysis) } returns result
        every { validator.validate(profile, result) } just Runs
        every { matrixBuilder.build(profile, analysis, result) } returns emptyList()
        every { matches.findByCandidateProfileIdAndVacancyId(profileId, vacancyId) } returns null
        every { matches.save(any()) } answers { firstArg() }

        AnalyzeVacancyUseCase(profiles, vacancies, analyses, matches, analyzer, matcher, validator, matrixBuilder,
            Clock.fixed(Instant.EPOCH, ZoneOffset.UTC)).execute(profileId, vacancyId)

        verifyOrder {
            profiles.findById(profileId)
            vacancies.findById(vacancyId)
            analyzer.analyze(vacancy)
            analyses.findByVacancyId(vacancyId)
            analyses.save(any())
            matcher.match(profile, vacancy, analysis)
            validator.validate(profile, result)
            matrixBuilder.build(profile, analysis, result)
            matches.findByCandidateProfileIdAndVacancyId(profileId, vacancyId)
            matches.save(any())
        }
    }
}
