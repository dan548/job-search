package th.sibraine.jobagent.rendering.infrastructure

import th.sibraine.jobagent.candidate.domain.*
import org.springframework.stereotype.Component
import java.net.URI

data class ResumeHtmlDocument(
    val html: String,
    val expectedTextBlocks: List<String>,
    val expectedLinkCount: Int,
)

@Component
class AtsSingleColumnHtmlRenderer {
    fun render(resume: StructuredResume, compact: Boolean = false): ResumeHtmlDocument {
        val blocks = visibleTextBlocks(resume)
        val linkCount = resume.contacts.count { contactHref(it) != null } + resume.projects.count { safeWebUrl(it.url) != null }
        val html = buildString {
            append("""<!DOCTYPE html><html xmlns="http://www.w3.org/1999/xhtml"><head><meta charset="UTF-8"/><style>""")
            append(CSS)
            append("</style></head><body")
            if (compact) append(" class=\"compact\"")
            append(">")
            if (resume.identity != null || resume.contacts.isNotEmpty()) {
                append("<header")
                if (resume.photo != null) append(" class=\"with-photo\"")
                append(">")
                resume.photo?.let { photo ->
                    append("<img class=\"photo\" src=\"").append(escapeAttribute(photo.dataUri)).append("\" alt=\"\"/>")
                    append("<div class=\"header-copy\">")
                }
                resume.identity?.let { identity ->
                    append("<h1>").append(escape(identity.fullName)).append("</h1>")
                    identity.headline?.takeIf { it.isNotBlank() }?.let {
                        append("<div class=\"headline\">").append(escape(it)).append("</div>")
                    }
                }
                if (resume.contacts.isNotEmpty()) {
                    append("<div class=\"contacts\">")
                    resume.contacts.forEachIndexed { index, contact ->
                        if (index > 0) append("<span class=\"separator\"> | </span>")
                        val href = contactHref(contact)
                        if (href != null) {
                            append("<a href=\"").append(escapeAttribute(href)).append("\">")
                                .append(escape(contactDisplayText(contact))).append("</a>")
                        } else {
                            append("<span>").append(escape(contactDisplayText(contact))).append("</span>")
                        }
                    }
                    append("</div>")
                }
                if (resume.photo != null) append("</div>")
                append("</header>")
            }
            resume.summary?.let { section("Summary") { paragraph(it.text) } }
            if (resume.skills.isNotEmpty()) section("Skills") {
                paragraph(resume.skills.joinToString(" | ") { it.name }, "skills")
            }
            if (resume.experiences.isNotEmpty()) section("Experience") {
                resume.experiences.forEach { value ->
                    experience(value, resume.projects.filter { it.experienceElementId == value.elementId })
                }
            }
            val standaloneProjects = resume.projects.filter { it.experienceElementId == null }
            if (standaloneProjects.isNotEmpty()) section("Projects") {
                standaloneProjects.forEach { project(it) }
            }
            if (resume.education.isNotEmpty()) section("Education") {
                resume.education.forEach { education(it) }
            }
            if (resume.certifications.isNotEmpty()) section("Certifications") {
                resume.certifications.forEach { certification(it) }
            }
            if (resume.languages.isNotEmpty()) section("Languages") {
                paragraph(resume.languages.joinToString(" | ") {
                    listOfNotNull(it.name, it.proficiency).joinToString(" - ")
                })
            }
            append("</body></html>")
        }
        return ResumeHtmlDocument(html, blocks.filter { it.isNotBlank() }, linkCount)
    }

    private fun StringBuilder.section(title: String, content: StringBuilder.() -> Unit) {
        append("<section><h2>").append(escape(title)).append("</h2>")
        content()
        append("</section>")
    }

    private fun StringBuilder.paragraph(text: String, cssClass: String = "") {
        append("<p")
        if (cssClass.isNotEmpty()) append(" class=\"").append(cssClass).append("\"")
        append(">").append(escape(text)).append("</p>")
    }

    private fun StringBuilder.experience(value: ResumeExperience, projects: List<ResumeProject>) {
        append("<article><table><tr><td><h3>").append(escape(value.role)).append("</h3></td><td class=\"date\">")
            .append(escape(dateRange(value.startDate, value.endDate, value.current))).append("</td></tr></table>")
        append("<div class=\"subhead\">").append(escape(listOfNotNull(value.company, value.location).joinToString(" | ")))
            .append("</div>")
        value.description?.takeIf { it.isNotBlank() }?.let { paragraph(it) }
        value.technologies.takeIf { it.isNotEmpty() }?.let {
            append("<div class=\"technologies\"><strong>Technologies:</strong> ")
                .append(escape(it.joinToString(" | "))).append("</div>")
        }
        bullets(value.achievements)
        projects.forEach { project(it, nested = true) }
        append("</article>")
    }

    private fun StringBuilder.project(value: ResumeProject, nested: Boolean = false) {
        if (!nested) append("<article>")
        append(if (nested) "<div class=\"nested-project\"><h4>" else "<h3>")
        val href = safeWebUrl(value.url)
        if (href != null) append("<a href=\"").append(escapeAttribute(href)).append("\">").append(escape(value.name)).append("</a>")
        else append(escape(value.name))
        append(if (nested) "</h4>" else "</h3>")
        value.description?.takeIf { it.isNotBlank() }?.let { paragraph(it) }
        bullets(value.achievements)
        append(if (nested) "</div>" else "</article>")
    }

    private fun StringBuilder.education(value: ResumeEducation) {
        append("<article><table><tr><td><h3>").append(escape(value.institution)).append("</h3></td><td class=\"date\">")
            .append(escape(dateRange(value.startDate, value.endDate, false))).append("</td></tr></table>")
        val detail = listOfNotNull(value.degree, value.fieldOfStudy).joinToString(", ")
        if (detail.isNotBlank()) append("<div class=\"subhead\">").append(escape(detail)).append("</div>")
        value.description?.takeIf { it.isNotBlank() }?.let { paragraph(it) }
        append("</article>")
    }

    private fun StringBuilder.certification(value: ResumeCertification) {
        append("<article><h3>").append(escape(value.name)).append("</h3>")
        val detail = listOfNotNull(value.issuer, value.issuedAt?.let(::formatDate)).joinToString(" | ")
        if (detail.isNotBlank()) append("<div class=\"subhead\">").append(escape(detail)).append("</div>")
        append("</article>")
    }

    private fun StringBuilder.bullets(items: List<ResumeTextElement>) {
        if (items.isEmpty()) return
        append("<ul>")
        items.forEach { append("<li>").append(escape(it.text)).append("</li>") }
        append("</ul>")
    }

    private fun visibleTextBlocks(resume: StructuredResume): List<String> = buildList {
        resume.identity?.let { add(it.fullName); it.headline?.let(::add) }
        resume.contacts.forEach { add(contactDisplayText(it)) }
        resume.summary?.let { add(it.text) }
        if (resume.skills.isNotEmpty()) add(resume.skills.joinToString(" | ") { it.name })
        resume.experiences.forEach {
            add(it.role); add(dateRange(it.startDate, it.endDate, it.current)); add(listOfNotNull(it.company, it.location).joinToString(" | "))
            it.description?.let(::add)
            if (it.technologies.isNotEmpty()) add("Technologies: ${it.technologies.joinToString(" | ")}")
            it.achievements.forEach { item -> add(item.text) }
        }
        resume.projects.forEach { add(it.name); it.description?.let(::add); it.achievements.forEach { item -> add(item.text) } }
        resume.education.forEach {
            add(it.institution); add(dateRange(it.startDate, it.endDate, false))
            add(listOfNotNull(it.degree, it.fieldOfStudy).joinToString(", ")); it.description?.let(::add)
        }
        resume.certifications.forEach { add(it.name); add(listOfNotNull(it.issuer, it.issuedAt?.let(::formatDate)).joinToString(" | ")) }
        if (resume.languages.isNotEmpty()) add(resume.languages.joinToString(" | ") { listOfNotNull(it.name, it.proficiency).joinToString(" - ") })
    }

    private fun contactHref(contact: ResumeContact): String? = when (contact.type) {
        ResumeContactType.EMAIL -> "mailto:${contact.value.trim()}"
        ResumeContactType.PHONE -> "tel:${contact.value.filter { it.isDigit() || it == '+' }}"
        ResumeContactType.WEBSITE, ResumeContactType.LINKEDIN, ResumeContactType.GITHUB -> safeWebUrl(contact.value)
        else -> null
    }

    /**
     * Labels from resume decomposition describe a field (for example, "Work preference"); they
     * are not the candidate's value. Keep a friendly label only for web links, where "LinkedIn"
     * can intentionally replace a long URL, and render the actual value everywhere else.
     */
    private fun contactDisplayText(contact: ResumeContact): String = when (contact.type) {
        ResumeContactType.WEBSITE, ResumeContactType.LINKEDIN, ResumeContactType.GITHUB ->
            contact.label?.takeIf { it.isNotBlank() } ?: contact.value
        else -> contact.value
    }

    private fun safeWebUrl(value: String?): String? {
        if (value.isNullOrBlank()) return null
        val raw = runCatching { URI(value.trim()) }.getOrNull() ?: return null
        if (raw.scheme != null && raw.scheme.lowercase() !in setOf("http", "https")) return null
        val candidate = if (raw.scheme != null) value.trim() else "https://${value.trim()}"
        return runCatching { URI(candidate) }.getOrNull()?.takeIf { it.scheme in setOf("http", "https") && it.host != null }?.toASCIIString()
    }

    private fun dateRange(start: ResumeDate?, end: ResumeDate?, current: Boolean): String =
        listOfNotNull(start?.let(::formatDate), if (current) "Present" else end?.let(::formatDate)).joinToString(" - ")

    private fun formatDate(date: ResumeDate): String = date.month?.let { "%02d/%04d".format(it, date.year) } ?: date.year.toString()

    private fun escape(value: String): String = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    private fun escapeAttribute(value: String): String = escape(value).replace("\"", "&quot;")

    companion object {
        private val CSS = """
            @page { size: A4; margin: 14mm 16mm 15mm; }
            * { box-sizing: border-box; }
            body { color: #17212b; font-family: 'Liberation Sans'; font-size: 9.5pt; line-height: 1.34; margin: 0; }
            header { border-bottom: 1.2pt solid #243b53; padding-bottom: 7pt; margin-bottom: 9pt; text-align: center; }
            header.with-photo { display: table; table-layout: fixed; width: 100%; text-align: left; }
            .photo { border-radius: 4pt; display: table-cell; height: 27mm; object-fit: cover; vertical-align: middle; width: 27mm; }
            .header-copy { display: table-cell; padding-left: 8pt; vertical-align: middle; }
            h1 { font-size: 22pt; line-height: 1.05; letter-spacing: .2pt; margin: 0 0 3pt; }
            .headline { color: #334e68; font-size: 11pt; font-weight: bold; margin-bottom: 4pt; }
            .contacts { color: #486581; font-size: 8.7pt; }
            a { color: #1f5f8b; text-decoration: none; }
            .separator { color: #9fb3c8; }
            section { margin-top: 8pt; }
            h2 { border-bottom: .7pt solid #9fb3c8; color: #243b53; font-size: 10pt; letter-spacing: .8pt; margin: 0 0 4pt; padding-bottom: 2pt; page-break-after: avoid; text-transform: uppercase; }
            article { margin: 0 0 6pt; page-break-inside: avoid; }
            h3 { font-size: 10pt; line-height: 1.2; margin: 0; }
            h4 { font-size: 9pt; line-height: 1.2; margin: 0; }
            table { border-collapse: collapse; table-layout: fixed; width: 100%; }
            td { padding: 0; vertical-align: top; }
            td.date { color: #486581; font-size: 8.5pt; text-align: right; white-space: nowrap; width: 29%; }
            .subhead { color: #486581; font-size: 8.8pt; font-weight: bold; margin: 1pt 0 2pt; }
            .technologies { color: #486581; font-size: 8.5pt; margin: 2pt 0 3pt; }
            p { margin: 2pt 0 4pt; orphans: 2; widows: 2; }
            p.skills { line-height: 1.45; }
            ul { margin: 2pt 0 0 14pt; padding: 0; }
            li { margin: 0 0 2pt; orphans: 2; padding-left: 2pt; page-break-inside: avoid; widows: 2; }
            .nested-project { border-left: 1pt solid #d9e2ec; margin: 4pt 0 0 4pt; padding-left: 7pt; page-break-inside: avoid; }
            body.compact { font-size: 8.7pt; line-height: 1.24; }
            body.compact header { margin-bottom: 6pt; padding-bottom: 5pt; }
            body.compact h1 { font-size: 19pt; }
            body.compact .photo { height: 23mm; width: 23mm; }
            body.compact section { margin-top: 5pt; }
            body.compact article { margin-bottom: 4pt; }
            body.compact p, body.compact li { margin-bottom: 1pt; }
        """.trimIndent()
    }
}
