package th.sibraine.jobagent.rendering.infrastructure

import th.sibraine.jobagent.candidate.domain.*
import th.sibraine.jobagent.rendering.domain.ResumeRenderingException
import th.sibraine.jobagent.tailoring.domain.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID

class OpenHtmlToPdfResumeRendererTest {
    private val htmlRenderer = AtsSingleColumnHtmlRenderer()

    @Test
    fun `renders ATS readable PDF with embedded fonts and clickable links`() {
        val rendered = renderer(maxPages = 3).render(variant(sampleResume()))

        assertTrue(rendered.bytes.take(4).toByteArray().contentEquals("%PDF".toByteArray()))
        assertEquals(1, rendered.pageCount)
        assertTrue(rendered.extractedText.contains("Алексей Иванов"))
        assertTrue(rendered.extractedText.contains("Kotlin backend services"))
        assertEquals(3, rendered.clickableLinkCount)

        (System.getProperty("cvRenderer.output") ?: System.getenv("CV_RENDERER_OUTPUT"))
            ?.takeIf { it.isNotBlank() }
            ?.let { output ->
            val path = Path.of(output)
            Files.createDirectories(path.parent)
            Files.write(path, rendered.bytes)
        }
    }

    @Test
    fun `rejects a resume exceeding configured page limit`() {
        val achievement = ResumeTextElement("achievement", "Improved a production service using Kotlin and PostgreSQL.", confirmed())
        val longResume = sampleResume().copy(
            experiences = sampleResume().experiences.map {
                it.copy(achievements = (1..180).map { index -> achievement.copy(elementId = "achievement-$index") })
            }
        )

        val error = assertThrows<ResumeRenderingException> { renderer(maxPages = 1).render(variant(longResume)) }
        assertEquals("RESUME_PAGE_LIMIT_EXCEEDED", error.code)
    }

    @Test
    fun `escapes resume content and only creates safe links`() {
        val resume = sampleResume().copy(
            identity = sampleResume().identity!!.copy(fullName = "Alex <script>alert(1)</script>"),
            contacts = listOf(
                ResumeContact("unsafe", ResumeContactType.WEBSITE, "javascript:alert(1)", metadata = confirmed())
            ),
        )

        val html = htmlRenderer.render(resume)

        assertTrue("&lt;script&gt;" in html.html)
        assertTrue("href=\"javascript:" !in html.html)
        assertEquals(1, html.expectedLinkCount) // The project URL remains valid; the unsafe contact is plain text.
    }

    @Test
    fun `renders contact value instead of a field label in the header`() {
        val resume = sampleResume().copy(
            contacts = listOf(
                ResumeContact(
                    "work-preference",
                    ResumeContactType.OTHER,
                    "Open to remote",
                    label = "Work preference",
                    metadata = confirmed(),
                )
            )
        )

        val html = htmlRenderer.render(resume)

        assertTrue("Open to remote" in html.html)
        assertTrue("Work preference" !in html.html)
        assertEquals(listOf("Алексей Иванов", "Senior Backend Engineer", "Open to remote"), html.expectedTextBlocks.take(3))
    }

    @Test
    fun `renders photo and keeps work projects inside their experience`() {
        val resume = sampleResume().copy(
            photo = ResumePhoto("data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="),
            projects = sampleResume().projects.map { it.copy(experienceElementId = "experience") },
        )

        val html = htmlRenderer.render(resume)
        val rendered = renderer(maxPages = 3).render(variant(resume))

        assertTrue("class=\"photo\"" in html.html)
        assertTrue("class=\"nested-project\"" in html.html)
        assertTrue("<h2>Projects</h2>" !in html.html)
        assertTrue(html.html.indexOf("Evidence-based Job Agent") < html.html.indexOf("<h2>Education</h2>"))
        assertEquals(1, rendered.pageCount)
    }

    private fun renderer(maxPages: Int) = OpenHtmlToPdfResumeRenderer(htmlRenderer, RenderedPdfValidator(maxPages))

    private fun variant(resume: StructuredResume) = ResumeVariant(
        variantId = UUID.randomUUID(),
        version = 1,
        vacancyId = UUID.randomUUID(),
        candidateProfileId = UUID.randomUUID(),
        baseImportId = UUID.randomUUID(),
        baseImportVersion = 1,
        templateId = CURRENT_TEMPLATE_ID,
        templateVersion = CURRENT_TEMPLATE_VERSION,
        plan = TailoringPlan(),
        resume = resume,
        diff = emptyList(),
        createdAt = Instant.parse("2026-08-17T00:00:00Z"),
    )

    private fun sampleResume() = StructuredResume(
        identity = ResumeIdentity("identity", "Алексей Иванов", "Senior Backend Engineer", confirmed()),
        contacts = listOf(
            ResumeContact("email", ResumeContactType.EMAIL, "alex@example.com", metadata = confirmed()),
            ResumeContact("github", ResumeContactType.GITHUB, "github.com/alex", metadata = confirmed()),
            ResumeContact("location", ResumeContactType.LOCATION, "Almaty, Kazakhstan", metadata = confirmed()),
        ),
        summary = ResumeTextElement(
            "summary",
            "Backend engineer focused on reliable distributed systems and pragmatic product delivery.",
            confirmed(),
        ),
        skills = listOf("Kotlin", "Spring Boot", "PostgreSQL", "Kafka", "Docker").mapIndexed { index, name ->
            ResumeSkill("skill-$index", name, metadata = confirmed())
        },
        experiences = listOf(
            ResumeExperience(
                elementId = "experience",
                company = "Example Systems",
                role = "Senior Backend Engineer",
                location = "Remote",
                startDate = ResumeDate(2022, 3),
                current = true,
                achievements = listOf(
                    ResumeTextElement("exp-a1", "Built Kotlin backend services processing 2M events per day.", confirmed()),
                    ResumeTextElement("exp-a2", "Reduced p95 latency by 38% through query and cache optimization.", confirmed()),
                    ResumeTextElement("exp-a3", "Led production reliability reviews across three product teams.", confirmed()),
                ),
                metadata = confirmed(),
            )
        ),
        projects = listOf(
            ResumeProject(
                elementId = "project",
                name = "Evidence-based Job Agent",
                description = "A service for traceable vacancy matching and resume tailoring.",
                url = "https://example.com/job-agent",
                achievements = listOf(
                    ResumeTextElement("project-a1", "Designed strict evidence validation for every tailored claim.", confirmed())
                ),
                skillElementIds = listOf("skill-0", "skill-1", "skill-2"),
                metadata = confirmed(),
            )
        ),
        education = listOf(
            ResumeEducation(
                "education", "Technical University", "BSc", "Computer Science", ResumeDate(2014), ResumeDate(2018),
                metadata = confirmed(),
            )
        ),
        certifications = listOf(
            ResumeCertification("certification", "Cloud Architecture", "Example Academy", ResumeDate(2024), metadata = confirmed())
        ),
        languages = listOf(
            ResumeLanguage("language-en", "English", "C1", confirmed()),
            ResumeLanguage("language-ru", "Русский", "Native", confirmed()),
        ),
    )

    private fun confirmed() = ResumeElementMetadata(reviewStatus = ResumeReviewStatus.CONFIRMED)
}
