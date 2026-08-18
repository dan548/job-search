package th.sibraine.jobagent.candidate.infrastructure

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import th.sibraine.jobagent.candidate.domain.ResumeBoundingBox
import th.sibraine.jobagent.candidate.domain.ResumeTextBlock

class ResumeDecompositionMapperTest {
    private val block = ResumeTextBlock(
        "skills",
        1,
        0,
        "AWS (RDS, SSM), Kotlin",
        ResumeBoundingBox(10.0, 10.0, 200.0, 20.0),
    )

    @Test
    fun `repairs unbalanced punctuation in decomposed skill names`() {
        val resume = ResumeDecompositionMapper(listOf(block)).map(
            decomposition(
                skill("AWS (RDS"),
                skill("SSM)"),
                skill(" Kotlin, "),
            ),
        )

        assertEquals(listOf("AWS (RDS)", "SSM", "Kotlin"), resume.skills.map { it.name })
    }

    @Test
    fun `drops punctuation-only skills and deduplicates after repair`() {
        val resume = ResumeDecompositionMapper(listOf(block)).map(
            decomposition(skill("AWS (RDS"), skill("AWS (RDS)"), skill(";")),
        )

        assertEquals(listOf("AWS (RDS)"), resume.skills.map { it.name })
    }

    private fun skill(name: String) = DecomposedSkill(name, null, listOf(block.blockId), 0.9)

    private fun decomposition(vararg skills: DecomposedSkill) = ResumeDecomposition(
        identity = null,
        summary = null,
        contacts = emptyList(),
        experiences = emptyList(),
        projects = emptyList(),
        education = emptyList(),
        certifications = emptyList(),
        languages = emptyList(),
        skills = skills.toList(),
    )
}
