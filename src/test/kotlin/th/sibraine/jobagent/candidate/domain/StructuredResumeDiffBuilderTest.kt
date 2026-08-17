package th.sibraine.jobagent.candidate.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class StructuredResumeDiffBuilderTest {
    private val builder = StructuredResumeDiffBuilder()

    @Test
    fun `reports added removed and modified elements`() {
        val reviewed = ResumeElementMetadata(reviewStatus = ResumeReviewStatus.CONFIRMED)
        val base = StructuredResume(
            summary = ResumeTextElement("summary", "Old summary", reviewed),
            skills = listOf(ResumeSkill("skill-old", "Java", metadata = reviewed)),
        )
        val target = StructuredResume(
            summary = ResumeTextElement("summary", "New summary"),
            skills = listOf(ResumeSkill("skill-new", "Kotlin")),
        )

        val diff = builder.build(1, base, 2, target)

        assertEquals(3, diff.changes.size)
        assertEquals(ResumeChangeType.MODIFIED, diff.changes.single { it.elementId == "summary" }.changeType)
        assertEquals(ResumeChangeType.REMOVED, diff.changes.single { it.elementId == "skill-old" }.changeType)
        assertEquals(ResumeChangeType.ADDED, diff.changes.single { it.elementId == "skill-new" }.changeType)
    }

    @Test
    fun `first import is diffed against empty baseline`() {
        val target = StructuredResume(skills = listOf(ResumeSkill("skill-kotlin", "Kotlin")))

        val diff = builder.build(null, null, 1, target)

        assertEquals(null, diff.baseVersion)
        assertEquals(listOf(ResumeChangeType.ADDED), diff.changes.map { it.changeType })
    }
}
