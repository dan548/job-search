package th.sibraine.jobagent.tailoring.domain

import th.sibraine.jobagent.candidate.domain.CandidateProfile
import th.sibraine.jobagent.candidate.domain.StructuredResume
import th.sibraine.jobagent.candidate.domain.elementRefs

class EvidenceIndex(resume: StructuredResume, profile: CandidateProfile) {
    private val texts: Map<EvidenceRef, String> = buildMap {
        resume.elementRefs().forEach { ref ->
            put(
                EvidenceRef(EvidenceKind.RESUME_ELEMENT, ref.elementId),
                listOfNotNull(ref.text, ref.metadata.provenance?.sourceText).joinToString("\n"),
            )
        }
        profile.facts.filter { it.verified }.forEach { put(EvidenceRef(EvidenceKind.PROFILE_FACT, it.factId), it.text) }
    }

    fun textOrNull(ref: EvidenceRef): String? = texts[EvidenceRef(ref.kind, ref.id)]

    fun resolve(refs: List<EvidenceRef>): List<EvidenceRef> = refs.map { it.copy(text = textOrNull(it)) }

    fun sourceText(refs: List<EvidenceRef>): String = refs.mapNotNull(::textOrNull).joinToString("\n")
}
