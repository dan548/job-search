package th.sibraine.jobagent.applying.api

import th.sibraine.jobagent.applying.application.ApplicationSettingsService
import th.sibraine.jobagent.applying.domain.AnswerCatalogEntry
import th.sibraine.jobagent.applying.domain.ApplicationSettings
import th.sibraine.jobagent.applying.domain.FormFieldTopic
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*

data class AnswerCatalogEntryRequest(
    val question: String,
    val value: String,
    val topic: FormFieldTopic = FormFieldTopic.UNKNOWN,
)

@RestController
@RequestMapping("/api/v1")
class ApplicationSettingsController(private val service: ApplicationSettingsService) {
    @GetMapping("/application-settings")
    fun settings(): ApplicationSettings = service.settings()

    @PutMapping("/application-settings")
    fun putSettings(@RequestBody request: ApplicationSettings): ApplicationSettings =
        service.putSettings(request)

    @GetMapping("/application-answers")
    fun catalog(): List<AnswerCatalogEntry> = service.catalog()

    @PutMapping("/application-answers/{key}")
    fun putCatalogEntry(
        @PathVariable key: String,
        @RequestBody request: AnswerCatalogEntryRequest,
    ): AnswerCatalogEntry = service.putCatalogEntry(
        AnswerCatalogEntry(key = key, question = request.question, value = request.value, topic = request.topic)
    )

    @DeleteMapping("/application-answers/{key}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deleteCatalogEntry(@PathVariable key: String) = service.deleteCatalogEntry(key)
}
