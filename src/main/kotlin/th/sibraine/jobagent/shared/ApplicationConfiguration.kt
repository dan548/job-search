package th.sibraine.jobagent.shared

import th.sibraine.jobagent.applying.domain.AnswerResolver
import th.sibraine.jobagent.applying.domain.ApplicationStateMachine
import th.sibraine.jobagent.applying.domain.SemanticFormFiller
import th.sibraine.jobagent.applying.domain.FormFieldClassifier
import th.sibraine.jobagent.matching.domain.MatchResultValidator
import th.sibraine.jobagent.matching.domain.RequirementEvidenceMatrixBuilder
import th.sibraine.jobagent.candidate.domain.StructuredResumeValidator
import th.sibraine.jobagent.tailoring.domain.TailoredResumeBuilder
import th.sibraine.jobagent.tailoring.domain.TailoringPlanBuilder
import th.sibraine.jobagent.tailoring.domain.TailoringPlanValidator
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

@Configuration
class ApplicationConfiguration {
    @Bean fun clock(): Clock = Clock.systemUTC()
    @Bean fun matchResultValidator() = MatchResultValidator()
    @Bean fun requirementEvidenceMatrixBuilder() = RequirementEvidenceMatrixBuilder()
    @Bean fun structuredResumeValidator() = StructuredResumeValidator()
    @Bean fun tailoringPlanValidator() = TailoringPlanValidator()
    @Bean fun tailoringPlanBuilder() = TailoringPlanBuilder()
    @Bean fun tailoredResumeBuilder() = TailoredResumeBuilder()
    @Bean fun formFieldClassifier() = FormFieldClassifier()
    @Bean fun answerResolver(classifier: FormFieldClassifier) = AnswerResolver(classifier)
    @Bean fun applicationStateMachine() = ApplicationStateMachine()
    @Bean fun semanticFormFiller() = SemanticFormFiller()
}
