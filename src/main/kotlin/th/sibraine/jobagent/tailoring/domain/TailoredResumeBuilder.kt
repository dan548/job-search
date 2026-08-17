package th.sibraine.jobagent.tailoring.domain

import th.sibraine.jobagent.candidate.domain.CandidateProfile
import th.sibraine.jobagent.candidate.domain.ResumeElementMetadata
import th.sibraine.jobagent.candidate.domain.ResumeProvenance
import th.sibraine.jobagent.candidate.domain.ResumeReviewStatus
import th.sibraine.jobagent.candidate.domain.ResumeTextElement
import th.sibraine.jobagent.candidate.domain.StructuredResume
import java.security.MessageDigest

class TailoredResumeBuilder {
    fun build(resume: StructuredResume, profile: CandidateProfile, plan: TailoringPlan): StructuredResume {
        val evidence = EvidenceIndex(resume, profile)
        val skills = plan.skillElementIds.mapNotNull { id -> resume.skills.firstOrNull { it.elementId == id } }
        val skillIds = skills.map { it.elementId }.toSet()
        return StructuredResume(
            identity = resume.identity,
            summary = plan.summary?.let { tailored -> textElement(resume.summary, tailored, evidence) },
            contacts = resume.contacts,
            experiences = plan.experiences.mapNotNull { tailored ->
                resume.experiences.firstOrNull { it.elementId == tailored.sourceElementId }?.let { source ->
                    source.copy(
                        achievements = tailored.achievements.map { achievement ->
                            textElement(source.achievements.bySourceOf(achievement), achievement, evidence)
                        },
                    )
                }
            },
            projects = plan.projects.mapNotNull { tailored ->
                resume.projects.firstOrNull { it.elementId == tailored.sourceElementId }?.let { source ->
                    source.copy(
                        achievements = tailored.achievements.map { achievement ->
                            textElement(source.achievements.bySourceOf(achievement), achievement, evidence)
                        },
                        skillElementIds = source.skillElementIds.filter { it in skillIds },
                    )
                }
            },
            education = resume.education,
            certifications = resume.certifications,
            languages = resume.languages,
            skills = skills,
        )
    }

    private fun List<ResumeTextElement>.bySourceOf(tailored: TailoredText): ResumeTextElement? =
        firstOrNull { it.elementId == tailored.sourceElementId }

    private fun textElement(
        source: ResumeTextElement?,
        tailored: TailoredText,
        evidence: EvidenceIndex,
    ): ResumeTextElement {
        if (source != null && source.text == tailored.text) return source
        return ResumeTextElement(
            elementId = source?.elementId ?: tailoredElementId(tailored),
            text = tailored.text,
            metadata = ResumeElementMetadata(
                provenance = source?.metadata?.provenance ?: ResumeProvenance(
                    sourceText = evidence.sourceText(tailored.evidence).ifBlank { tailored.text },
                ),
                confidence = source?.metadata?.confidence,
                reviewStatus = ResumeReviewStatus.UNREVIEWED,
            ),
        )
    }

    private fun tailoredElementId(tailored: TailoredText): String {
        val input = (tailored.text + tailored.evidence.joinToString("|") { "${it.kind}:${it.id}" }).toByteArray()
        val hash = MessageDigest.getInstance("SHA-256").digest(input)
            .take(10).joinToString("") { "%02x".format(it) }
        return "tailored-$hash"
    }
}
