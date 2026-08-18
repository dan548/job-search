package th.sibraine.jobagent.tailoring.domain

import th.sibraine.jobagent.candidate.domain.*
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.UUID

class TailoringPlanValidatorTest {
    private val validator = TailoringPlanValidator()

    @Test
    fun `accepts a plan that only reuses confirmed resume content`() {
        validator.validate(
            TailoringPlan(
                summary = reuse("summary-1", "Backend engineer"),
                experiences = listOf(
                    TailoredExperience("exp-1", listOf(reuse("ach-1", "Built 3 Kotlin services")))
                ),
                projects = listOf(TailoredProject("prj-1", listOf(reuse("prj-ach-1", "Automated resume parsing")))),
                skillElementIds = listOf("skill-kotlin", "skill-sql"),
            ),
            resume(),
            profile(),
        )
    }

    @Test
    fun `accepts a rewrite supported by a verified profile fact`() {
        validator.validate(
            TailoringPlan(
                experiences = listOf(
                    TailoredExperience(
                        "exp-1",
                        listOf(
                            TailoredText(
                                text = "Delivered Kotlin and Spring Boot services",
                                evidence = listOf(
                                    EvidenceRef(EvidenceKind.RESUME_ELEMENT, "ach-1"),
                                    EvidenceRef(EvidenceKind.PROFILE_FACT, "fact-verified"),
                                ),
                                sourceElementId = "ach-1",
                            )
                        ),
                    )
                ),
            ),
            resume(),
            profile(),
        )
    }

    @Test
    fun `rejects a statement without evidence`() {
        val error = assertThrows<InvalidTailoringPlanException> {
            validator.validate(
                TailoringPlan(summary = TailoredText(text = "Led a platform team")),
                resume(),
                profile(),
            )
        }
        assertTrue(error.message!!.contains("no evidence"))
    }

    @Test
    fun `rejects evidence that is not confirmed or verified`() {
        assertThrows<InvalidTailoringPlanException> {
            validator.validate(
                TailoringPlan(
                    summary = TailoredText(
                        text = "Backend engineer",
                        evidence = listOf(EvidenceRef(EvidenceKind.PROFILE_FACT, "fact-unverified")),
                    )
                ),
                resume(),
                profile(),
            )
        }
        assertThrows<InvalidTailoringPlanException> {
            validator.validate(
                TailoringPlan(
                    summary = TailoredText(
                        text = "Backend engineer",
                        evidence = listOf(EvidenceRef(EvidenceKind.RESUME_ELEMENT, "unreviewed-1")),
                    )
                ),
                resume(),
                profile(),
            )
        }
    }

    @Test
    fun `rejects numbers that the cited evidence does not state`() {
        val error = assertThrows<InvalidTailoringPlanException> {
            validator.validate(
                TailoringPlan(
                    experiences = listOf(
                        TailoredExperience(
                            "exp-1",
                            listOf(
                                TailoredText(
                                    text = "Built 12 Kotlin services",
                                    evidence = listOf(EvidenceRef(EvidenceKind.RESUME_ELEMENT, "ach-1")),
                                    sourceElementId = "ach-1",
                                )
                            ),
                        )
                    )
                ),
                resume(),
                profile(),
            )
        }
        assertTrue(error.message!!.contains("12"))
    }

    @Test
    fun `rejects an achievement moved to a foreign experience`() {
        assertThrows<InvalidTailoringPlanException> {
            validator.validate(
                TailoringPlan(
                    experiences = listOf(
                        TailoredExperience("exp-1", listOf(reuse("prj-ach-1", "Automated resume parsing")))
                    )
                ),
                resume(),
                profile(),
            )
        }
    }

    @Test
    fun `rejects foreign narrative evidence even when source element is omitted`() {
        assertThrows<InvalidTailoringPlanException> {
            validator.validate(
                TailoringPlan(
                    experiences = listOf(
                        TailoredExperience(
                            "exp-1",
                            listOf(
                                TailoredText(
                                    text = "Automated resume parsing",
                                    evidence = listOf(EvidenceRef(EvidenceKind.RESUME_ELEMENT, "prj-ach-1")),
                                    sourceElementId = null,
                                )
                            ),
                        )
                    )
                ),
                resume(),
                profile(),
            )
        }
    }

    @Test
    fun `rejects unknown experiences, projects and skills`() {
        assertThrows<InvalidTailoringPlanException> {
            validator.validate(TailoringPlan(experiences = listOf(TailoredExperience("exp-unknown"))), resume(), profile())
        }
        assertThrows<InvalidTailoringPlanException> {
            validator.validate(TailoringPlan(projects = listOf(TailoredProject("prj-unknown"))), resume(), profile())
        }
        assertThrows<InvalidTailoringPlanException> {
            validator.validate(TailoringPlan(skillElementIds = listOf("skill-kafka")), resume(), profile())
        }
    }

    @Test
    fun `rejects a repeated source element`() {
        assertThrows<InvalidTailoringPlanException> {
            validator.validate(
                TailoringPlan(
                    experiences = listOf(TailoredExperience("exp-1"), TailoredExperience("exp-1")),
                ),
                resume(),
                profile(),
            )
        }
    }

    private fun reuse(elementId: String, text: String) = TailoredText(
        text = text,
        evidence = listOf(EvidenceRef(EvidenceKind.RESUME_ELEMENT, elementId)),
        sourceElementId = elementId,
    )

    private fun profile() = CandidateProfile(
        UUID.randomUUID(),
        GeneralInfo("Test Candidate"),
        facts = listOf(
            CandidateFact("fact-verified", FactType.SKILL, "Built services with Kotlin and Spring Boot", true),
            CandidateFact("fact-unverified", FactType.SKILL, "Used Kafka", false),
        ),
    )

    private fun resume() = StructuredResume(
        summary = ResumeTextElement("summary-1", "Backend engineer", confirmed()),
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
                achievements = listOf(ResumeTextElement("prj-ach-1", "Automated resume parsing", confirmed())),
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
