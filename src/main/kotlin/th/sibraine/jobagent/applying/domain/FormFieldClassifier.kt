package th.sibraine.jobagent.applying.domain

/**
 * Maps an observed form field to a canonical topic with deterministic keyword rules. Rules are
 * ordered: the first matching rule wins, so narrow topics are listed before broader ones.
 */
class FormFieldClassifier {
    fun classify(field: ObservedFormField): FormFieldTopic {
        val haystack = listOfNotNull(field.label, field.placeholder, field.fieldKey)
            .joinToString(" ", prefix = " ", postfix = " ") { it.lowercase() }
        if (field.type == FormFieldType.FILE) {
            return when {
                COVER_LETTER_WORDS.any { haystack.contains(it) } -> FormFieldTopic.COVER_LETTER
                RESUME_WORDS.any { haystack.contains(it) } -> FormFieldTopic.RESUME_FILE
                else -> FormFieldTopic.UNKNOWN
            }
        }
        RULES.firstOrNull { rule -> rule.second.any { haystack.contains(it) } }?.let { return it.first }
        val exact = listOfNotNull(field.label, field.fieldKey).map { it.trim().lowercase() }
        return EXACT_RULES.entries.firstOrNull { entry -> exact.any { it == entry.key } }?.value
            ?: FormFieldTopic.UNKNOWN
    }

    fun catalogKey(topic: FormFieldTopic, question: String): String =
        if (topic != FormFieldTopic.UNKNOWN) topic.name else "QUESTION:${normalize(question)}"

    private fun normalize(question: String): String = question
        .lowercase()
        .map { if (it.isLetterOrDigit()) it else ' ' }
        .joinToString("")
        .split(' ')
        .filter { it.isNotBlank() }
        .joinToString("-")
        .take(120)

    private companion object {
        private val RESUME_WORDS = listOf("resume", "cv", "резюме")
        private val COVER_LETTER_WORDS = listOf("cover letter", "motivation letter", "сопроводительн")

        private val RULES: List<Pair<FormFieldTopic, List<String>>> = listOf(
            FormFieldTopic.COVER_LETTER to COVER_LETTER_WORDS,
            FormFieldTopic.DEMOGRAPHIC to listOf(
                "gender", "race", "ethnic", "disability", "veteran", "sexual orientation",
                "date of birth", "birthday", "your age", "marital",
                " пол ", "национальност", "инвалидн", "возраст", "дата рождения", "семейное положение",
            ),
            FormFieldTopic.BACKGROUND_CHECK to listOf(
                "background check", "criminal", "convicted", "conviction", "security clearance",
                "судимост", "проверка службы безопасности",
            ),
            FormFieldTopic.REFERENCES to listOf("reference", "рекоменд"),
            FormFieldTopic.CURRENT_SALARY to listOf(
                "current salary", "current compensation", "present salary", "last salary",
                "текущая зарплата", "текущий доход", "нынешняя зарплата",
            ),
            FormFieldTopic.DESIRED_SALARY to listOf(
                "desired salary", "expected salary", "salary expectation", "compensation expectation",
                "expected compensation", "desired compensation", "salary requirement", "rate expectation",
                "зарплатные ожидания", "желаемая зарплата", "ожидаемая зарплата", "ожидания по зарплате",
                "salary", "compensation", "зарплат",
            ),
            FormFieldTopic.VISA_SPONSORSHIP to listOf(
                "sponsorship", "sponsor", "visa", "work permit", "residence permit",
                "виза", "спонсор", "разрешение на работу", "вид на жительство",
            ),
            FormFieldTopic.WORK_AUTHORIZATION to listOf(
                "authorized to work", "authorised to work", "work authorization", "work authorisation",
                "eligible to work", "right to work", "legally able to work",
                "право на работу", "легально работать",
            ),
            FormFieldTopic.RELOCATION to listOf("relocat", "переезд", "релокац"),
            FormFieldTopic.REMOTE_PREFERENCE to listOf(
                "remote", "hybrid", "onsite", "on-site", "work from office",
                "удалённ", "удаленн", "гибрид", "из офиса",
            ),
            FormFieldTopic.NOTICE_PERIOD to listOf("notice period", "notice", "срок уведомления", "отработк"),
            FormFieldTopic.START_DATE to listOf(
                "start date", "available to start", "earliest start", "when can you start",
                "дата выхода", "когда готовы приступить", "готовы приступить",
            ),
            FormFieldTopic.YEARS_OF_EXPERIENCE to listOf(
                "years of experience", "years experience", "how many years", "лет опыта", "сколько лет опыта",
            ),
            FormFieldTopic.LINKEDIN to listOf("linkedin"),
            FormFieldTopic.GITHUB to listOf("github"),
            FormFieldTopic.WEBSITE to listOf("website", "portfolio", "personal site", "сайт", "портфолио"),
            FormFieldTopic.EMAIL to listOf("email", "e-mail", "почта"),
            FormFieldTopic.PHONE to listOf("phone", "mobile", "telephone", "телефон"),
            FormFieldTopic.LOCATION to listOf(
                "city", "location", "where are you based", "current residence",
                "город", "местоположение", "адрес",
            ),
            FormFieldTopic.FIRST_NAME to listOf("first name", "given name", "имя"),
            FormFieldTopic.LAST_NAME to listOf("last name", "family name", "surname", "фамилия"),
            FormFieldTopic.FULL_NAME to listOf("full name", "your name", "фио", "полное имя"),
        )

        private val EXACT_RULES = mapOf(
            "name" to FormFieldTopic.FULL_NAME,
            "candidate" to FormFieldTopic.FULL_NAME,
            "email" to FormFieldTopic.EMAIL,
            "tel" to FormFieldTopic.PHONE,
        )
    }
}
