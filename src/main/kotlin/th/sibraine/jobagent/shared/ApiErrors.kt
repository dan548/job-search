package th.sibraine.jobagent.shared

import th.sibraine.jobagent.applying.domain.InvalidApplicationTransitionException
import th.sibraine.jobagent.matching.domain.InvalidMatchResultException
import th.sibraine.jobagent.shared.ai.AiAnalysisException
import th.sibraine.jobagent.candidate.domain.ResumeParsingException
import th.sibraine.jobagent.rendering.domain.ResumeRenderingException
import th.sibraine.jobagent.tailoring.domain.InvalidTailoringPlanException
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.slf4j.LoggerFactory
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.multipart.MaxUploadSizeExceededException

data class ApiError(val code: String, val message: String, val details: Map<String, Any?> = emptyMap())
class NotFoundException(val code: String, override val message: String) : RuntimeException(message)
class ConflictException(val code: String, override val message: String) : RuntimeException(message)

@RestControllerAdvice
class ApiExceptionHandler {
    private val logger = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(NotFoundException::class)
    fun notFound(error: NotFoundException) = ResponseEntity.status(HttpStatus.NOT_FOUND)
        .body(ApiError(error.code, error.message))

    @ExceptionHandler(ConflictException::class)
    fun conflict(error: ConflictException) = ResponseEntity.status(HttpStatus.CONFLICT)
        .body(ApiError(error.code, error.message))

    @ExceptionHandler(InvalidApplicationTransitionException::class)
    fun invalidApplicationState(error: InvalidApplicationTransitionException) =
        ResponseEntity.status(HttpStatus.CONFLICT)
            .body(ApiError("INVALID_APPLICATION_STATE", error.message ?: "Invalid application state"))

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun validation(error: MethodArgumentNotValidException) = ResponseEntity.badRequest().body(
        ApiError(
            "VALIDATION_ERROR",
            "Request validation failed",
            mapOf("fields" to error.bindingResult.fieldErrors.associate { it.field to (it.defaultMessage ?: "invalid") }),
        )
    )

    @ExceptionHandler(IllegalArgumentException::class, HttpMessageNotReadableException::class)
    fun badRequest(error: Exception) = ResponseEntity.badRequest()
        .body(ApiError("INVALID_REQUEST", error.message ?: "Invalid request"))

    @ExceptionHandler(InvalidMatchResultException::class)
    fun invalidMatch(error: InvalidMatchResultException) = ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
        .body(ApiError("INVALID_MATCH_RESULT", error.message ?: "Invalid match result"))

    @ExceptionHandler(InvalidTailoringPlanException::class)
    fun invalidTailoringPlan(error: InvalidTailoringPlanException) =
        ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
            .body(ApiError("INVALID_TAILORING_PLAN", error.message ?: "Invalid tailoring plan"))

    @ExceptionHandler(AiAnalysisException::class)
    fun aiAnalysis(error: AiAnalysisException): ResponseEntity<ApiError> {
        logger.error("AI structured analysis failed", error)
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
            .body(ApiError("AI_ANALYSIS_FAILED", "AI analysis is temporarily unavailable"))
    }

    @ExceptionHandler(ResumeParsingException::class)
    fun resumeParsing(error: ResumeParsingException) = ResponseEntity.unprocessableEntity()
        .body(ApiError(error.code, error.message))

    @ExceptionHandler(ResumeRenderingException::class)
    fun resumeRendering(error: ResumeRenderingException) = ResponseEntity.unprocessableEntity()
        .body(ApiError(error.code, error.message))

    @ExceptionHandler(MaxUploadSizeExceededException::class)
    fun uploadTooLarge(error: MaxUploadSizeExceededException) = ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
        .body(ApiError("PDF_SIZE_LIMIT_EXCEEDED", "PDF must not exceed 5 MB"))

    @ExceptionHandler(Exception::class)
    fun unexpected(error: Exception, request: HttpServletRequest): ResponseEntity<ApiError> {
        logger.error("Unhandled error for {}", request.requestURI, error)
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ApiError("INTERNAL_ERROR", "Unexpected server error", mapOf("path" to request.requestURI)))
    }
}
