package th.sibraine.jobagent.shared.ai

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.web.client.RestClient

@ConfigurationProperties("job-agent.ai.openai")
data class OpenAiProperties(
    val enabled: Boolean = false,
    val apiKey: String = "",
    val model: String = "gpt-5.6",
    val baseUrl: String = "https://api.openai.com",
)

class AiAnalysisException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

fun interface StructuredOutputClient {
    fun generate(systemPrompt: String, input: String, outputName: String, schema: Map<String, Any>): String
}

@Configuration
@EnableConfigurationProperties(OpenAiProperties::class)
@ConditionalOnProperty(prefix = "job-agent.ai.openai", name = ["enabled"], havingValue = "true")
class OpenAiStructuredOutputConfiguration {
    @Bean
    fun openAiRestClient(builder: RestClient.Builder, properties: OpenAiProperties): RestClient {
        require(properties.apiKey.isNotBlank()) {
            "OPENAI_API_KEY must be set when OpenAI analysis is enabled"
        }
        return builder
            .baseUrl(properties.baseUrl.trimEnd('/'))
            .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer ${properties.apiKey}")
            .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .build()
    }

    @Bean
    fun structuredOutputClient(
        openAiRestClient: RestClient,
        objectMapper: ObjectMapper,
        properties: OpenAiProperties,
    ): StructuredOutputClient = OpenAiResponsesClient(openAiRestClient, objectMapper, properties.model)
}

class OpenAiResponsesClient(
    private val restClient: RestClient,
    private val objectMapper: ObjectMapper,
    private val model: String,
) : StructuredOutputClient {
    override fun generate(
        systemPrompt: String,
        input: String,
        outputName: String,
        schema: Map<String, Any>,
    ): String {
        val request = mapOf(
            "model" to model,
            "store" to false,
            "input" to listOf(
                mapOf("role" to "system", "content" to systemPrompt),
                mapOf("role" to "user", "content" to input),
            ),
            "text" to mapOf(
                "format" to mapOf(
                    "type" to "json_schema",
                    "name" to outputName,
                    "strict" to true,
                    "schema" to schema,
                ),
            ),
        )

        val response = try {
            restClient.post()
                .uri("/v1/responses")
                .body(request)
                .retrieve()
                .body(JsonNode::class.java)
                ?: throw AiAnalysisException("OpenAI returned an empty response")
        } catch (error: AiAnalysisException) {
            throw error
        } catch (error: Exception) {
            throw AiAnalysisException("OpenAI request failed", error)
        }

        if (response.path("status").asText() != "completed") {
            val reason = response.path("incomplete_details").path("reason").asText("unknown")
            throw AiAnalysisException("OpenAI response was not completed: $reason")
        }

        val content = response.path("output").flatMap { it.path("content").toList() }
        content.firstOrNull { it.path("type").asText() == "refusal" }?.let {
            throw AiAnalysisException("OpenAI refused to produce structured output")
        }
        return content.firstOrNull { it.path("type").asText() == "output_text" }
            ?.path("text")
            ?.asText()
            ?.takeIf(String::isNotBlank)
            ?: throw AiAnalysisException("OpenAI response did not contain structured output")
    }
}
