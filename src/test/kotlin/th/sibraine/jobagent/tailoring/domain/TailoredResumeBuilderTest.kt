package th.sibraine.jobagent.tailoring.domain

import th.sibraine.jobagent.candidate.domain.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class TailoredResumeBuilderTest {
    private val builder = TailoredResumeBuilder()

    @Test
    fun `applies selection and order and keeps untouched sections`() {
        val tailored = builder.build(
            resume(),
            profile(),
            TailoringPlan(
                summary = reuse("summary-1", "Backend engineer"),
                experiences = listOf(
                    TailoredExperience(
                        "exp-1",
                        listOf(reuse("ach-2", "Maintained a PostgreSQL cluster"), reuse("ach-1", "Built 3 Kotlin services")),
                    )
                ),
                projects = listOf(TailoredProject("prj-1")),
                skillElementIds = listOf("skill-sql"),
            ),
        )

        assertEquals(listOf("ach-2", "ach-1"), tailored.experiences.single().achievements.map { it.elementId })
        assertEquals(listOf("skill-sql"), tailored.skills.map { it.elementId })
        assertEquals(emptyList<String>(), tailored.projects.single().skillElementIds)
        assertEquals(listOf("contact-1"), tailored.contacts.map { it.elementId })
        assertEquals("Test Candidate", tailored.identity?.fullName)
        StructuredResumeValidator().validate(tailored)
    }

    @Test
    fun `keeps verbatim reuse confirmed and marks a rewrite for review`() {
        val tailored = builder.build(
            resume(),
            profile(),
            TailoringPlan(
                summary = TailoredText(
                    text = "Backend engineer focused on Kotlin services",
                    evidence = listOf(
                        EvidenceRef(EvidenceKind.RESUME_ELEMENT, "summary-1"),
                        EvidenceRef(EvidenceKind.PROFILE_FACT, "fact-verified"),
                    ),
                    sourceElementId = "summary-1",
                ),
                experiences = listOf(TailoredExperience("exp-1", listOf(reuse("ach-1", "Built 3 Kotlin services")))),
            ),
        )

        val summary = tailored.summary!!
        assertEquals("summary-1", summary.elementId)
        assertEquals(ResumeReviewStatus.UNREVIEWED, summary.metadata.reviewStatus)
        assertEquals("Original summary block", summary.metadata.provenance?.sourceText)
        assertEquals(
            ResumeReviewStatus.CONFIRMED,
            tailored.experiences.single().achievements.single().metadata.reviewStatus,
        )
    }

    @Test
    fun `composed text gets a stable id and provenance from its evidence`() {
        val plan = TailoringPlan(
            experiences = listOf(
                TailoredExperience(
                    "exp-1",
                    listOf(
                        TailoredText(
                            text = "Built Kotlin services and ran PostgreSQL",
                            evidence = listOf(
                                EvidenceRef(EvidenceKind.RESUME_ELEMENT, "ach-1"),
                                EvidenceRef(EvidenceKind.RESUME_ELEMENT, "ach-2"),
                            ),
                        )
                    ),
                )
            )
        )

        val first = builder.build(resume(), profile(), plan).experiences.single().achievements.single()
        val second = builder.build(resume(), profile(), plan).experiences.single().achievements.single()

        assertEquals(first.elementId, second.elementId)
        assertTrue(first.elementId.startsWith("tailored-"))
        assertEquals(ResumeReviewStatus.UNREVIEWED, first.metadata.reviewStatus)
        assertTrue(first.metadata.provenance!!.sourceText.contains("Built 3 Kotlin services"))
        assertTrue(first.metadata.provenance!!.sourceText.contains("Maintained a PostgreSQL cluster"))
    }

    private fun reuse(elementId: String, text: String) = TailoredText(
        text = text,
        evidence = listOf(EvidenceRef(EvidenceKind.RESUME_ELEMENT, elementId)),
        sourceElementId = elementId,
    )

    private fun profile() = CandidateProfile(
        UUID.randomUUID(),
        GeneralInfo("Test Candidate"),
        facts = listOf(CandidateFact("fact-verified", FactType.SKILL, "Built Kotlin services", true)),
    )

    private fun resume() = StructuredResume(
        identity = ResumeIdentity("identity-1", "Test Candidate", metadata = confirmed()),
        summary = ResumeTextElement(
            "summary-1",
            "Backend engineer",
            ResumeElementMetadata(ResumeProvenance("Original summary block"), 0.9, ResumeReviewStatus.CONFIRMED),
        ),
        contacts = listOf(ResumeContact("contact-1", ResumeContactType.EMAIL, "a@example.com", metadata = confirmed())),
        experiences = listOf(
            ResumeExperience(
                elementId = "exp-1",
                company = "Acme",
                role = "Backend Engineer",
                achievements = listOf(
                    ResumeTextElement("ach-1", "Built 3 Kotlin services", confirmed()),
                    ResumeTextElement("ach-2", "Maintained a PostgreSQL cluster", confirmed()),
                ),
                metadata = confirmed(),
            )
        ),
        projects = listOf(
            ResumeProject(
                elementId = "prj-1",
                name = "Job Agent",
                skillElementIds = listOf("skill-kotlin"),
                metadata = confirmed(),
            )
        ),
        skills = listOf(
            ResumeSkill("skill-kotlin", "Kotlin", metadata = confirmed()),
            ResumeSkill("skill-sql", "PostgreSQL", metadata = confirmed()),
        ),
    )

    private fun confirmed() = ResumeElementMetadata(reviewStatus = ResumeReviewStatus.CONFIRMED)
}
