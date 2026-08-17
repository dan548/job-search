package th.sibraine.jobagent.applying.domain

import java.security.MessageDigest

/**
 * Stable identity of a single form observation: the page URL, the blocking challenges and every
 * attribute a runner reported for every field.
 *
 * The run idempotency fingerprint and the derived keys of a resumed browser run are both built from
 * it, so an unchanged page always maps back to the same run while a page that grew a field, changed a
 * label or moved to the next step always maps to a new one.
 */
object FormObservationDigest {
    fun of(
        formUrl: String?,
        challenges: Set<BrowserChallenge>,
        fields: List<ObservedFormField>,
    ): String {
        val payload = buildString {
            append(formUrl.orEmpty()).append('\n')
            append(challenges.sortedBy { it.name }.joinToString(",") { it.name }).append('\n')
            fields.forEach { field ->
                append(field.fieldKey).append('|').append(field.label).append('|').append(field.type)
                    .append('|').append(field.required).append('|').append(field.placeholder.orEmpty())
                    .append('|').append(field.options.joinToString("\u001f")).append('|')
                    .append(field.maxLength ?: "").append('|').append(field.locator.orEmpty())
                    .append('|').append(field.page.orEmpty()).append('\n')
            }
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(payload.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}
