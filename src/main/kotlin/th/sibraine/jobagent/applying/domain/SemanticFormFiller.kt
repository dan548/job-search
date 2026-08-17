package th.sibraine.jobagent.applying.domain

data class BrowserAuditEntry(
    val event: String,
    val fieldKey: String? = null,
    val detailCode: String? = null,
)

data class SemanticFillPlan(
    val actions: List<BrowserFillAction>,
    val skippedFieldKeys: List<String>,
    val audit: List<BrowserAuditEntry>,
)

/** Converts already-approved application answers into browser operations without guessing values. */
class SemanticFormFiller {
    fun plan(
        snapshot: BrowserFormSnapshot,
        answers: List<ApplicationAnswer>,
        artifact: (ApplicationArtifact) -> BrowserArtifactPayload,
        artifacts: List<ApplicationArtifact>,
    ): SemanticFillPlan {
        val answersByKey = answers.associateBy { it.fieldKey }
        val artifactsById = artifacts.associateBy { it.artifactId }
        val actions = mutableListOf<BrowserFillAction>()
        val skipped = mutableListOf<String>()
        val audit = mutableListOf<BrowserAuditEntry>()

        snapshot.fields.forEach { field ->
            val locator = field.locator?.takeIf { it.isNotBlank() }
            val answer = answersByKey[field.fieldKey]
            if (locator == null || answer == null || answer.source == AnswerSource.DECLINED_BY_USER) {
                skipped += field.fieldKey
                audit += BrowserAuditEntry("FIELD_SKIPPED", field.fieldKey, skipReason(locator, answer))
                return@forEach
            }

            val action = when (field.type) {
                FormFieldType.FILE -> answer.artifactId?.let(artifactsById::get)?.let { stored ->
                    BrowserFillAction(
                        fieldKey = field.fieldKey,
                        locator = locator,
                        type = field.type,
                        artifact = artifact(stored),
                    )
                }
                FormFieldType.CHECKBOX -> parseBoolean(answer.value)?.let { checked ->
                    BrowserFillAction(field.fieldKey, locator, field.type, checked = checked)
                }
                FormFieldType.SELECT, FormFieldType.RADIO -> optionValue(answer.value, field.options)?.let { value ->
                    BrowserFillAction(field.fieldKey, locator, field.type, value = value)
                }
                else -> answer.value?.let { value ->
                    BrowserFillAction(field.fieldKey, locator, field.type, value = value)
                }
            }

            if (action == null) {
                skipped += field.fieldKey
                audit += BrowserAuditEntry("FIELD_SKIPPED", field.fieldKey, "UNUSABLE_ANSWER")
            } else {
                actions += action
                // Deliberately record only the field key and operation type, never its value.
                audit += BrowserAuditEntry("FIELD_PLANNED", field.fieldKey, field.type.name)
            }
        }
        return SemanticFillPlan(actions, skipped, audit)
    }

    private fun skipReason(locator: String?, answer: ApplicationAnswer?): String = when {
        locator == null -> "MISSING_LOCATOR"
        answer == null -> "NO_APPROVED_ANSWER"
        else -> "DECLINED_BY_USER"
    }

    private fun optionValue(value: String?, options: List<String>): String? {
        if (value == null) return null
        if (options.isEmpty()) return value
        return options.firstOrNull { it.equals(value, ignoreCase = true) }
    }

    private fun parseBoolean(value: String?): Boolean? = when (value?.trim()?.lowercase()) {
        "true", "yes", "y", "1", "on" -> true
        "false", "no", "n", "0", "off" -> false
        else -> null
    }
}
