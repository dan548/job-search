package th.sibraine.jobagent.matching.domain

import th.sibraine.jobagent.candidate.domain.CandidateProfile
import th.sibraine.jobagent.vacancy.domain.Vacancy

interface VacancyAnalyzer {
    fun analyze(vacancy: Vacancy): VacancyAnalysis
}

interface CandidateMatcher {
    fun match(profile: CandidateProfile, vacancy: Vacancy, analysis: VacancyAnalysis): MatchResult
}
