package th.sibraine.jobagent.applying.domain

import th.sibraine.jobagent.candidate.domain.*
import th.sibraine.jobagent.tailoring.domain.EvidenceKind
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

class AnswerResolverTest {
    private val resolver = AnswerResolver()

    @Test
    fun `fills contact fields from the confirmed resume with evidence`() {
        val resolution = resolve(
            field("full_name", "Full name"),
            field("email", "Email", type = FormFieldType.EMAIL),
            field("city", "City"),
        )

        assertEquals(emptyList<ApplicationQuestion>(), resolution.questions)
        assertEquals(
            listOf("Ada Lovelace", "ada@example.com", "Warsaw"),
            resolution.answers.map { it.value },
        )
        assertTrue(resolution.answers.all { it.source == AnswerSource.RESUME })
        val email = resolution.answers.single { it.fieldKey == "email" }.evidence.single()
        assertEquals(EvidenceKind.RESUME_ELEMENT, email.kind)
        assertEquals("contact-email", email.id)
    }

    @Test
    fun `never infers salary, work authorization or relocation from resume and profile`() {
        val resolution = resolve(
            field("salary", "Desired salary"),
            field("authorized", "Are you authorized to work in Poland?", options = listOf("Yes", "No")),
            field("relocation", "Are you willing to relocate?", options = listOf("Yes", "No")),
            field("gender", "Gender", required = false),
        )

        assertEquals(emptyList<ApplicationAnswer>(), resolution.answers)
        assertEquals(
            listOf(
                FormFieldTopic.DESIRED_SALARY,
                FormFieldTopic.WORK_AUTHORIZATION,
                FormFieldTopic.RELOCATION,
                FormFieldTopic.DEMOGRAPHIC,
            ),
            resolution.questions.map { it.topic },
        )
        assertTrue(resolution.questions.all { it.reason == QuestionReason.SENSITIVE_TOPIC })
        assertEquals(3, resolution.blockingQuestions.size)
    }

    @Test
    fun `answers salary and relocation from explicit settings only`() {
        val settings = ApplicationSettings(
            desiredSalary = SalaryExpectation(BigDecimal("28000"), "PLN", SalaryPeriod.MONTH, negotiable = true),
            relocation = RelocationPreference(willing = false),
            requiresSponsorship = true,
        )

        val resolution = resolve(
            field("salary", "Desired salary"),
            field("salary_number", "Expected salary", type = FormFieldType.NUMBER),
            field("relocation", "Willing to relocate?", options = listOf("Yes", "No")),
            field("sponsorship", "Do you require visa sponsorship?", options = listOf("Yes", "No")),
            settings = settings,
        )

        assertEquals(emptyList<ApplicationQuestion>(), resolution.questions)
        assertEquals(
            listOf("28000 PLN per month, negotiable", "28000", "No", "Yes"),
            resolution.answers.map { it.value },
        )
        assertTrue(resolution.answers.all { it.source == AnswerSource.SETTINGS })
    }

    @Test
    fun `reuses a catalog answer and asks again when it does not fit the field`() {
        val catalog = listOf(
            AnswerCatalogEntry(FormFieldTopic.YEARS_OF_EXPERIENCE.name, "Years of experience", "7"),
            AnswerCatalogEntry(FormFieldTopic.NOTICE_PERIOD.name, "Notice period", "Two months"),
        )

        val resolution = resolve(
            field("experience", "Years of experience"),
            field("notice", "Notice period", options = listOf("Immediately", "1 month")),
            catalog = catalog,
        )

        assertEquals("7", resolution.answers.single().value)
        assertEquals(AnswerSource.CATALOG, resolution.answers.single().source)
        assertEquals(QuestionReason.OPTION_MISMATCH, resolution.questions.single().reason)
    }

    @Test
    fun `takes the user decision over every other source and records a decline`() {
        val resolution = resolve(
            field("city", "City"),
            field("gender", "Gender", required = false),
            userAnswers = mapOf(
                "city" to UserAnswer("Berlin"),
                "gender" to UserAnswer(declined = true),
            ),
        )

        val city = resolution.answers.single { it.fieldKey == "city" }
        assertEquals("Berlin", city.value)
        assertEquals(AnswerSource.USER, city.source)
        val gender = resolution.answers.single { it.fieldKey == "gender" }
        assertNull(gender.value)
        assertEquals(AnswerSource.DECLINED_BY_USER, gender.source)
    }

    @Test
    fun `attaches the rendered resume to the resume upload and asks for other files`() {
        val artifact = ApplicationArtifact(
            artifactId = UUID.randomUUID(),
            draftId = UUID.randomUUID(),
            type = ApplicationArtifactType.RESUME_PDF,
            fileName = "resume.pdf",
            contentType = "application/pdf",
            sha256 = "0".repeat(64),
            byteSize = 1024,
            createdAt = Instant.EPOCH,
        )

        val resolution = resolve(
            field("cv", "Upload your CV", type = FormFieldType.FILE),
            field("portfolio_file", "Portfolio deck", type = FormFieldType.FILE),
            artifact = artifact,
        )

        val resume = resolution.answers.single()
        assertEquals(artifact.artifactId, resume.artifactId)
        assertEquals(AnswerSource.ARTIFACT, resume.source)
        assertEquals(QuestionReason.MISSING_ARTIFACT, resolution.questions.single().reason)
    }

    @Test
    fun `asks about an unknown free-form question instead of guessing`() {
        val resolution = resolve(field("q1", "Why do you want to join Acme?", type = FormFieldType.TEXTAREA))

        val question = resolution.questions.single()
        assertEquals(FormFieldTopic.UNKNOWN, question.topic)
        assertEquals(QuestionReason.UNKNOWN_FIELD, question.reason)
        assertEquals("QUESTION:why-do-you-want-to-join-acme", question.catalogKey)
    }

    private fun resolve(
        vararg fields: ObservedFormField,
        settings: ApplicationSettings = ApplicationSettings(),
        catalog: List<AnswerCatalogEntry> = emptyList(),
        userAnswers: Map<String, UserAnswer> = emptyMap(),
        artifact: ApplicationArtifact? = null,
    ) = resolver.resolve(
        AnswerResolutionRequest(
            fields = fields.toList(),
            resume = resume(),
            profile = profile(),
            settings = settings,
            catalog = catalog,
            userAnswers = userAnswers,
            resumeArtifact = artifact,
        )
    )

    private fun field(
        key: String,
        label: String,
        type: FormFieldType = FormFieldType.TEXT,
        required: Boolean = true,
        options: List<String> = emptyList(),
    ) = ObservedFormField(fieldKey = key, label = label, type = type, required = required, options = options)

    private fun profile() = CandidateProfile(
        id = UUID.randomUUID(),
        generalInfo = GeneralInfo("Ada Lovelace", "Backend Engineer"),
        skills = setOf("Kotlin"),
        facts = listOf(
            CandidateFact("fact-relocation", FactType.OTHER, "Worked remotely from Berlin in 2024", true),
        ),
    )

    private fun resume() = StructuredResume(
        identity = ResumeIdentity("identity-1", "Ada Lovelace", "Backend Engineer", confirmed()),
        summary = ResumeTextElement("summary-1", "Backend engineer with Kotlin experience", confirmed()),
        contacts = listOf(
            ResumeContact("contact-email", ResumeContactType.EMAIL, "ada@example.com", metadata = confirmed()),
            ResumeContact("contact-city", ResumeContactType.LOCATION, "Warsaw", metadata = confirmed()),
        ),
        skills = listOf(ResumeSkill("skill-kotlin", "Kotlin", metadata = confirmed())),
    )

    private fun confirmed() = ResumeElementMetadata(reviewStatus = ResumeReviewStatus.CONFIRMED)
}
