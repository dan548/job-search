package th.sibraine.jobagent.applying.domain

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class SemanticFormFillerTest {
    private val filler = SemanticFormFiller()

    @Test
    fun `plans standard controls and never copies values into audit`() {
        val artifactId = UUID.randomUUID()
        val artifact = ApplicationArtifact(
            artifactId, UUID.randomUUID(), ApplicationArtifactType.RESUME_PDF,
            "resume.pdf", "application/pdf", "abc", 3, Instant.EPOCH,
        )
        val snapshot = BrowserFormSnapshot(
            url = "https://jobs.example/apply",
            checkpoint = "page-v1",
            fields = listOf(
                ObservedFormField("email", "Email", FormFieldType.EMAIL, locator = "#email"),
                ObservedFormField(
                    "auth", "Authorized?", FormFieldType.RADIO,
                    options = listOf("Yes", "No"), locator = "[name=auth]",
                ),
                ObservedFormField("terms", "Terms", FormFieldType.CHECKBOX, locator = "#terms"),
                ObservedFormField("cv", "Resume", FormFieldType.FILE, locator = "#cv"),
            ),
        )
        val answers = listOf(
            answer("email", "ada@example.com"),
            answer("auth", "yes"),
            answer("terms", "true"),
            ApplicationAnswer(
                "cv", FormFieldTopic.RESUME_FILE, "Resume", null,
                AnswerSource.ARTIFACT, artifactId = artifactId,
            ),
        )

        val plan = filler.plan(snapshot, answers, { stored ->
            BrowserArtifactPayload(stored.artifactId, stored.fileName, stored.contentType, byteArrayOf(1, 2, 3))
        }, listOf(artifact))

        assertEquals(4, plan.actions.size)
        assertEquals("Yes", plan.actions.single { it.fieldKey == "auth" }.value)
        assertEquals(true, plan.actions.single { it.fieldKey == "terms" }.checked)
        assertArrayEquals(byteArrayOf(1, 2, 3), plan.actions.single { it.fieldKey == "cv" }.artifact!!.content)
        assertFalse(plan.audit.joinToString().contains("ada@example.com"))
        assertTrue(plan.audit.all { it.detailCode != "yes" && it.detailCode != "true" })
        assertFalse(plan.actions.joinToString().contains("ada@example.com"))
        assertFalse(plan.actions.joinToString().contains("1, 2, 3"))
    }

    @Test
    fun `skips fields without a stable locator or usable explicit answer`() {
        val snapshot = BrowserFormSnapshot(
            "https://jobs.example/apply",
            listOf(
                ObservedFormField("name", "Name"),
                ObservedFormField("country", "Country", FormFieldType.SELECT, options = listOf("PL"), locator = "#country"),
            ),
            "page-v1",
        )

        val plan = filler.plan(snapshot, listOf(answer("name", "Ada"), answer("country", "UK")), { error("unused") }, emptyList())

        assertTrue(plan.actions.isEmpty())
        assertEquals(listOf("name", "country"), plan.skippedFieldKeys)
        assertEquals(listOf("MISSING_LOCATOR", "UNUSABLE_ANSWER"), plan.audit.map { it.detailCode })
    }

    private fun answer(fieldKey: String, value: String) = ApplicationAnswer(
        fieldKey, FormFieldTopic.UNKNOWN, fieldKey, value, AnswerSource.USER,
    )
}
