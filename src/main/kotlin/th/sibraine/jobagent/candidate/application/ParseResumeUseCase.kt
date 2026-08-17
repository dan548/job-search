package th.sibraine.jobagent.candidate.application

import th.sibraine.jobagent.candidate.domain.ParsedResume
import th.sibraine.jobagent.candidate.domain.ResumeDocument
import th.sibraine.jobagent.candidate.domain.ResumeDocumentParser
import org.springframework.stereotype.Service

@Service
class ParseResumeUseCase(private val parser: ResumeDocumentParser) {
    fun execute(fileName: String, content: ByteArray): ParsedResume {
        val safeFileName = fileName.substringAfterLast('/').substringAfterLast('\\').take(255)
            .ifBlank { "resume.pdf" }
        return parser.parse(ResumeDocument(safeFileName, content))
    }
}
