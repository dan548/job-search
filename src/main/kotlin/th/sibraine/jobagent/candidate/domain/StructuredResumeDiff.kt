package th.sibraine.jobagent.candidate.domain

enum class ResumeChangeType { ADDED, REMOVED, MODIFIED }

data class ResumeImportDiff(
    val baseVersion: Long?,
    val targetVersion: Long,
    val changes: List<ResumeDiffChange>,
)

data class ResumeDiffChange(
    val section: String,
    val elementId: String,
    val changeType: ResumeChangeType,
    val previousText: String? = null,
    val currentText: String? = null,
    val previousReviewStatus: ResumeReviewStatus? = null,
    val currentReviewStatus: ResumeReviewStatus? = null,
)

class StructuredResumeDiffBuilder {
    fun build(baseVersion: Long?, base: StructuredResume?, targetVersion: Long, target: StructuredResume) =
        ResumeImportDiff(baseVersion, targetVersion, changes(base, target))

    fun changes(base: StructuredResume?, target: StructuredResume): List<ResumeDiffChange> {
        val previous = base?.elementRefs().orEmpty().associateBy { it.elementId }
        val current = target.elementRefs().associateBy { it.elementId }
        return (previous.keys + current.keys).mapNotNull { elementId ->
            val before = previous[elementId]
            val after = current[elementId]
            when {
                before == null && after != null -> after.toChange(ResumeChangeType.ADDED, null)
                before != null && after == null -> before.toChange(ResumeChangeType.REMOVED, null)
                before != null && after != null && before.element != after.element ->
                    after.toChange(ResumeChangeType.MODIFIED, before)
                else -> null
            }
        }.sortedWith(compareBy(ResumeDiffChange::section, ResumeDiffChange::elementId))
    }

    private fun ResumeElementRef.toChange(type: ResumeChangeType, previous: ResumeElementRef?) = ResumeDiffChange(
        section = section,
        elementId = elementId,
        changeType = type,
        previousText = if (type == ResumeChangeType.ADDED) null else previous?.text ?: text,
        currentText = if (type == ResumeChangeType.REMOVED) null else text,
        previousReviewStatus = if (type == ResumeChangeType.ADDED) null else {
            previous?.metadata?.reviewStatus ?: metadata.reviewStatus
        },
        currentReviewStatus = if (type == ResumeChangeType.REMOVED) null else metadata.reviewStatus,
    )
}
