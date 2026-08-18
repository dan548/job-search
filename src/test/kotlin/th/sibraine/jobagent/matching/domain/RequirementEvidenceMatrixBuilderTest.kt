package th.sibraine.jobagent.matching.domain

import th.sibraine.jobagent.candidate.domain.CandidateFact
import th.sibraine.jobagent.candidate.domain.CandidateProfile
import th.sibraine.jobagent.candidate.domain.FactType
import th.sibraine.jobagent.candidate.domain.GeneralInfo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.UUID

class RequirementEvidenceMatrixBuilderTest {
    private val builder = RequirementEvidenceMatrixBuilder()

    @Test
    fun `builds auditable rows and does not expose unverified facts`() {
        val profile = CandidateProfile(
            UUID.randomUUID(),
            GeneralInfo("Candidate"),
            facts = listOf(
                CandidateFact("verified", FactType.SKILL, "Built services with Kotlin", true),
                CandidateFact("draft", FactType.SKILL, "Used Kafka", false),
            ),
        )
        val analysis = VacancyAnalysis(
            role = "Backend Engineer",
            seniority = null,
            requiredSkills = listOf("Kotlin", "PostgreSQL"),
            preferredSkills = listOf("Kafka"),
            niceToHave = listOf("kafka"),
            locationConstraints = listOf("Remote in EU"),
        )
        val match = MatchResult(
            score = 60,
            recommendation = Recommendation.MAYBE,
            matchedRequirements = listOf(MatchedRequirement("kotlin", 0.9, listOf("verified", "draft"))),
            missingRequirements = listOf(
                MissingRequirement("PostgreSQL", RequirementImportance.HARD_REQUIREMENT),
            ),
            reasoningSummary = "Partial match",
        )

        val rows = builder.build(profile, analysis, match)

        assertEquals(4, rows.size)
        val kotlin = rows.single { it.requirement == "Kotlin" }
        val postgres = rows.single { it.requirement == "PostgreSQL" }
        val kafka = rows.single { it.requirement == "Kafka" }
        val location = rows.single { it.requirement == "Remote in EU" }
        assertEquals(RequirementStatus.MATCHED, kotlin.status)
        assertEquals(listOf("verified"), kotlin.evidence.map { it.factId })
        assertEquals(RequirementStatus.MISSING, postgres.status)
        assertEquals(RequirementImportance.SOFT_REQUIREMENT, kafka.importance)
        assertEquals(
            listOf(RequirementSource.PREFERRED_SKILL, RequirementSource.NICE_TO_HAVE),
            kafka.sources,
        )
        assertEquals(RequirementStatus.UNASSESSED, location.status)
    }

    @Test
    fun `marks a hard blocker explicitly`() {
        val profile = CandidateProfile(UUID.randomUUID(), GeneralInfo("Candidate"))
        val analysis = VacancyAnalysis(
            role = "Engineer",
            seniority = null,
            workAuthorizationConstraints = listOf("EU work authorization"),
        )
        val match = MatchResult(
            score = 0,
            recommendation = Recommendation.REJECT,
            hardBlockers = listOf("EU work authorization"),
            reasoningSummary = "Blocked",
        )

        assertEquals(RequirementStatus.BLOCKED, builder.build(profile, analysis, match).single().status)
    }

    @Test
    fun `collapses repeated requirements from a real vacancy into auditable themes`() {
        val profile = CandidateProfile(UUID.randomUUID(), GeneralInfo("Candidate"))
        val analysis = VacancyAnalysis(
            role = "Kotlin Java Internal Tools Developer",
            seniority = null,
            requiredSkills = listOf(
                "Java and/or Kotlin on JVM",
                "Kotlin/JVM",
                "Gradle",
                "Practical experience with Python",
                "Solid SQL knowledge (SQLite, MySQL, PostgreSQL)",
            ),
            hardRequirements = listOf(
                "6+ years of experience with Java and/or Kotlin on JVM",
                "Good understanding of the JVM ecosystem",
                "Dependency management",
                "Modular builds",
                "Object-oriented programming",
                "SOLID principles",
                "Clean architecture",
                "Ability to write clean, reliable, high-performance code and cover critical logic with tests",
                "Debugging, profiling, logging, and performance optimization",
                "Linux, common tools, and Linux ecosystem",
                "Understanding of Kubernetes, Docker and nearby technologies",
                "Understanding of 2D / 3D technologies and software, for example OpenGL, WebGL, Raytracing, Blender",
                "Ability to work with math-heavy, data-heavy, or algorithmic tasks",
            ),
            languageRequirements = listOf("Fluent in English"),
            locationConstraints = listOf("Tbilisi", "Belgrade", "Lisbon", "Madrid", "Riga", "Tallinn", "Valencia", "Yerevan"),
            softRequirements = listOf("Remote"),
        )
        val match = MatchResult(
            score = 52,
            recommendation = Recommendation.MAYBE,
            matchedRequirements = listOf(
                MatchedRequirement("Good understanding of the JVM ecosystem, particularly Gradle, modular builds, and debugging", 0.9, emptyList()),
                MatchedRequirement("Ability to cover critical logic with tests", 0.8, emptyList()),
                MatchedRequirement("Understanding of Docker and nearby technologies", 0.8, emptyList()),
                MatchedRequirement("Professional communication in English", 0.8, emptyList()),
                MatchedRequirement("Ability to work with algorithmic or math-heavy tasks", 0.8, emptyList()),
            ),
            missingRequirements = listOf(
                MissingRequirement("6+ years of experience with Java and/or Kotlin on JVM; only 5+ years is verified", RequirementImportance.HARD_REQUIREMENT),
                MissingRequirement("Solid SQL knowledge demonstrated through verified experience", RequirementImportance.HARD_REQUIREMENT),
                MissingRequirement("Knowledge of Linux, common tools, and Linux ecosystem demonstrated through verified experience", RequirementImportance.HARD_REQUIREMENT),
                MissingRequirement("Experience with Blender, rendering, 2D/3D processing, geometry, or model conversion", RequirementImportance.HARD_REQUIREMENT),
            ),
            reasoningSummary = "Partial match",
        )

        val rows = builder.build(profile, analysis, match)

        assertEquals(9, rows.size, rows.joinToString { it.requirement })
        val jvm = rows.single { it.requirement == "Java, Kotlin и экосистема JVM" }
        assertEquals(RequirementStatus.MISSING, jvm.status)
        assertEquals(9, jvm.relatedRequirements.size)
        assertEquals(1, rows.count { it.requirement == "Локация и переезд" })
        assertEquals(8, rows.single { it.requirement == "Локация и переезд" }.relatedRequirements.size)
        assertEquals(1, rows.count { it.requirement == "Архитектура, качество кода и тестирование" })
        assertEquals(1, rows.count { it.requirement == "Python, Blender и 2D/3D-технологии" })
    }
}
