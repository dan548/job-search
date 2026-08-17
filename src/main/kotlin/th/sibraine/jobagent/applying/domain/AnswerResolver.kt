package th.sibraine.jobagent.applying.domain

import th.sibraine.jobagent.candidate.domain.CandidateProfile
import th.sibraine.jobagent.candidate.domain.ResumeContactType
import th.sibraine.jobagent.candidate.domain.StructuredResume
import th.sibraine.jobagent.tailoring.domain.EvidenceKind
import th.sibraine.jobagent.tailoring.domain.EvidenceRef

data class UserAnswer(val value: String? = null, val declined: Boolean = false)

data class AnswerResolutionRequest(
    val fields: List<ObservedFormField>,
    val resume: StructuredResume,
    val profile: CandidateProfile,
    val settings: ApplicationSettings = ApplicationSettings(),
    val catalog: List<AnswerCatalogEntry> = emptyList(),
    val userAnswers: Map<String, UserAnswer> = emptyMap(),
    val resumeArtifact: ApplicationArtifact? = null,
)

data class AnswerResolution(
    val answers: List<ApplicationAnswer> = emptyList(),
    val questions: List<ApplicationQuestion> = emptyList(),
) {
    val blockingQuestions: List<ApplicationQuestion> get() = questions.filter { it.required }
}

/**
 * Chooses an answer for every observed field, or reports a question for the user.
 *
 * Only [DERIVABLE_TOPICS] may be taken from the confirmed resume and profile. Salary, work
 * authorization, relocation, experience length and demographic questions are answered exclusively
 * from explicit settings, the reusable answer catalog or a user decision — they are never inferred
 * from resume text, vacancy text or any other indirect data.
 */
class AnswerResolver(private val classifier: FormFieldClassifier = FormFieldClassifier()) {
    fun resolve(request: AnswerResolutionRequest): AnswerResolution {
        val catalog = request.catalog.associateBy { it.key }
        val answers = mutableListOf<ApplicationAnswer>()
        val questions = mutableListOf<ApplicationQuestion>()

        request.fields.forEach { field ->
            val topic = classifier.classify(field)
            val catalogKey = classifier.catalogKey(topic, field.label)
            val candidate = candidate(field, topic, catalogKey, request, catalog)
            when {
                candidate == null -> questions += question(field, topic, catalogKey, missingReason(topic))
                candidate.declined -> answers += ApplicationAnswer(
                    fieldKey = field.fieldKey,
                    topic = topic,
                    question = field.label,
                    value = null,
                    source = AnswerSource.DECLINED_BY_USER,
                )

                candidate.source == AnswerSource.ARTIFACT -> answers += ApplicationAnswer(
                    fieldKey = field.fieldKey,
                    topic = topic,
                    question = field.label,
                    value = candidate.value,
                    source = AnswerSource.ARTIFACT,
                    artifactId = candidate.artifactId,
                )

                else -> {
                    val value = candidate.value.orEmpty()
                    val problem = validate(field, value)
                    if (problem != null) {
                        questions += question(field, topic, catalogKey, problem)
                    } else {
                        answers += ApplicationAnswer(
                            fieldKey = field.fieldKey,
                            topic = topic,
                            question = field.label,
                            value = canonical(field, value),
                            source = candidate.source,
                            evidence = candidate.evidence,
                        )
                    }
                }
            }
        }
        return AnswerResolution(answers, questions)
    }

    private data class Candidate(
        val value: String?,
        val source: AnswerSource,
        val evidence: List<EvidenceRef> = emptyList(),
        val artifactId: java.util.UUID? = null,
        val declined: Boolean = false,
    )

    private fun candidate(
        field: ObservedFormField,
        topic: FormFieldTopic,
        catalogKey: String,
        request: AnswerResolutionRequest,
        catalog: Map<String, AnswerCatalogEntry>,
    ): Candidate? {
        request.userAnswers[field.fieldKey]?.let { user ->
            return if (user.declined) Candidate(null, AnswerSource.DECLINED_BY_USER, declined = true)
            else user.value?.takeIf { it.isNotBlank() }?.let { Candidate(it, AnswerSource.USER) }
        }
        if (field.type == FormFieldType.FILE) {
            val artifact = request.resumeArtifact?.takeIf { topic == FormFieldTopic.RESUME_FILE } ?: return null
            return Candidate(artifact.fileName, AnswerSource.ARTIFACT, artifactId = artifact.artifactId)
        }
        catalog[catalogKey]?.let { entry ->
            if (entry.value.isNotBlank()) return Candidate(entry.value, AnswerSource.CATALOG)
        }
        fromSettings(field, topic, request.settings)?.let { return Candidate(it, AnswerSource.SETTINGS) }
        if (topic in DERIVABLE_TOPICS) return derive(topic, request.resume, request.profile)
        return null
    }

    private fun fromSettings(
        field: ObservedFormField,
        topic: FormFieldTopic,
        settings: ApplicationSettings,
    ): String? = when (topic) {
        FormFieldTopic.DESIRED_SALARY -> settings.desiredSalary?.let { salary(it, field) }
        FormFieldTopic.VISA_SPONSORSHIP -> settings.requiresSponsorship?.let { yesNo(it, field) }
        FormFieldTopic.RELOCATION -> settings.relocation?.let { relocation(it, field) }
        FormFieldTopic.REMOTE_PREFERENCE -> settings.remotePreference
        FormFieldTopic.NOTICE_PERIOD -> settings.noticePeriod
        FormFieldTopic.START_DATE -> settings.earliestStartDate
        // A list of authorizations does not answer an employer-specific yes/no question by itself.
        FormFieldTopic.WORK_AUTHORIZATION -> settings.workAuthorizations
            .takeIf { it.isNotEmpty() && !field.isChoice() }
            ?.joinToString("; ") { "${it.country}: ${it.status}" }

        else -> null
    }

    private fun derive(topic: FormFieldTopic, resume: StructuredResume, profile: CandidateProfile): Candidate? {
        val identity = resume.identity
        fun name(): Candidate? = identity?.let {
            Candidate(it.fullName, AnswerSource.RESUME, evidence(it.elementId, it.fullName))
        } ?: profile.generalInfo.displayName.takeIf { it.isNotBlank() }
            ?.let { Candidate(it, AnswerSource.PROFILE) }

        fun contact(type: ResumeContactType): Candidate? = resume.contacts.firstOrNull { it.type == type }
            ?.let { Candidate(it.value, AnswerSource.RESUME, evidence(it.elementId, it.value)) }

        return when (topic) {
            FormFieldTopic.FULL_NAME -> name()
            FormFieldTopic.FIRST_NAME -> name()?.let { full ->
                full.value?.trim()?.substringBefore(' ')?.takeIf { it.isNotBlank() }?.let { full.copy(value = it) }
            }

            FormFieldTopic.LAST_NAME -> name()?.let { full ->
                full.value?.trim()?.substringAfter(' ', "")?.trim()?.takeIf { it.isNotBlank() }
                    ?.let { full.copy(value = it) }
            }

            FormFieldTopic.EMAIL -> contact(ResumeContactType.EMAIL)
            FormFieldTopic.PHONE -> contact(ResumeContactType.PHONE)
            FormFieldTopic.LOCATION -> contact(ResumeContactType.LOCATION)
            FormFieldTopic.LINKEDIN -> contact(ResumeContactType.LINKEDIN)
            FormFieldTopic.GITHUB -> contact(ResumeContactType.GITHUB)
            FormFieldTopic.WEBSITE -> contact(ResumeContactType.WEBSITE)
            else -> null
        }
    }

    private fun salary(expectation: SalaryExpectation, field: ObservedFormField): String {
        val amount = expectation.amount.stripTrailingZeros().toPlainString()
        if (field.type == FormFieldType.NUMBER) return amount
        val period = expectation.period.name.lowercase()
        val negotiable = if (expectation.negotiable) ", negotiable" else ""
        return "$amount ${expectation.currency} per $period$negotiable"
    }

    private fun relocation(preference: RelocationPreference, field: ObservedFormField): String {
        if (field.isChoice() || field.type == FormFieldType.CHECKBOX) return yesNo(preference.willing, field)
        val locations = preference.locations.takeIf { it.isNotEmpty() }?.joinToString(", ")
        return listOfNotNull(
            if (preference.willing) "Willing to relocate" else "Not willing to relocate",
            locations?.let { "to $it" },
            preference.notes,
        ).joinToString(" ")
    }

    private fun yesNo(value: Boolean, field: ObservedFormField): String {
        if (field.type == FormFieldType.CHECKBOX) return value.toString()
        val words = if (value) YES_WORDS else NO_WORDS
        return field.options.firstOrNull { option -> words.any { option.trim().lowercase() == it } }
            ?: if (value) "Yes" else "No"
    }

    private fun validate(field: ObservedFormField, value: String): QuestionReason? = when {
        value.isBlank() -> QuestionReason.NO_EXPLICIT_ANSWER
        field.options.isNotEmpty() && field.options.none { it.equals(value, ignoreCase = true) } ->
            QuestionReason.OPTION_MISMATCH

        field.maxLength != null && value.length > field.maxLength -> QuestionReason.VALUE_TOO_LONG
        else -> null
    }

    private fun canonical(field: ObservedFormField, value: String): String =
        field.options.firstOrNull { it.equals(value, ignoreCase = true) } ?: value

    private fun missingReason(topic: FormFieldTopic): QuestionReason = when {
        topic in SENSITIVE_TOPICS -> QuestionReason.SENSITIVE_TOPIC
        topic == FormFieldTopic.UNKNOWN -> QuestionReason.UNKNOWN_FIELD
        else -> QuestionReason.NO_EXPLICIT_ANSWER
    }

    private fun question(
        field: ObservedFormField,
        topic: FormFieldTopic,
        catalogKey: String,
        reason: QuestionReason,
    ) = ApplicationQuestion(
        fieldKey = field.fieldKey,
        topic = topic,
        question = field.label,
        reason = if (field.type == FormFieldType.FILE) QuestionReason.MISSING_ARTIFACT else reason,
        required = field.required,
        options = field.options,
        catalogKey = catalogKey,
    )

    private fun evidence(elementId: String, text: String) =
        listOf(EvidenceRef(EvidenceKind.RESUME_ELEMENT, elementId, text))

    private fun ObservedFormField.isChoice() =
        type == FormFieldType.SELECT || type == FormFieldType.RADIO || options.isNotEmpty()

    private companion object {
        private val YES_WORDS = setOf("yes", "y", "true", "да")
        private val NO_WORDS = setOf("no", "n", "false", "нет")
    }
}
