package th.sibraine.jobagent.candidate.domain

data class ResumeDocument(
    val fileName: String,
    val content: ByteArray,
)

data class ParsedResume(
    val fileName: String,
    val pageCount: Int,
    val extractedText: String,
    val detectedSkills: List<String>,
    val factCandidates: List<CandidateFact>,
    val warnings: List<String> = emptyList(),
    val textBlocks: List<ResumeTextBlock> = emptyList(),
    val extractionMethod: ResumeExtractionMethod = ResumeExtractionMethod.TEXT_LAYER,
)

data class ResumeTextBlock(
    val blockId: String,
    val pageNumber: Int,
    val order: Int,
    val text: String,
    val boundingBox: ResumeBoundingBox,
    val confidence: Double? = null,
    val coordinateSpace: ResumeCoordinateSpace = ResumeCoordinateSpace.PDF_POINTS,
)

enum class ResumeExtractionMethod { TEXT_LAYER, OCR }
enum class ResumeCoordinateSpace { PDF_POINTS, IMAGE_PIXELS }

data class ResumeOcrResult(
    val textBlocks: List<ResumeTextBlock>,
    val warnings: List<String> = emptyList(),
)

fun interface ResumeOcrEngine {
    fun extract(document: ResumeDocument): ResumeOcrResult
}

interface ResumeDocumentParser {
    fun parse(document: ResumeDocument): ParsedResume
}

fun interface ResumeDecomposer {
    fun decompose(resume: ParsedResume): StructuredResume
}

enum class ResumeConfirmationMode { REPLACE, ENRICH }

class ResumeParsingException(
    val code: String,
    override val message: String,
) : RuntimeException(message)
