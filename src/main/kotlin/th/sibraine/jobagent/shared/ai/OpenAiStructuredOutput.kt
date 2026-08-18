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
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient
import java.net.http.HttpClient
import java.net.http.HttpTimeoutException
import java.net.SocketTimeoutException
import java.time.Duration

@ConfigurationProperties("job-agent.ai.openai")
data class OpenAiProperties(
    val enabled: Boolean = false,
    val apiKey: String = "",
    val model: String = "gpt-5.6",
    val baseUrl: String = "https://api.openai.com",
    val connectTimeoutSeconds: Long = 10,
    val readTimeoutSeconds: Long = 180,
)

open class AiAnalysisException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
class AiRequestTimeoutException(cause: Throwable) : AiAnalysisException("OpenAI request timed out", cause)

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
        require(properties.connectTimeoutSeconds in 1..120) {
            "OPENAI_CONNECT_TIMEOUT_SECONDS must be between 1 and 120"
        }
        require(properties.readTimeoutSeconds in 10..600) {
            "OPENAI_READ_TIMEOUT_SECONDS must be between 10 and 600"
        }
        val httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(properties.connectTimeoutSeconds))
            .build()
        val requestFactory = JdkClientHttpRequestFactory(httpClient).apply {
            setReadTimeout(Duration.ofSeconds(properties.readTimeoutSeconds))
        }
        return builder
            .baseUrl(properties.baseUrl.trimEnd('/'))
            .requestFactory(requestFactory)
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
        } catch (error: ResourceAccessException) {
            if (error.isTimeout()) throw AiRequestTimeoutException(error)
            throw AiAnalysisException("OpenAI request failed", error)
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

    private fun Throwable.isTimeout(): Boolean = generateSequence(this) { it.cause }
        .any { cause ->
            cause is HttpTimeoutException || cause is SocketTimeoutException ||
                cause.message?.contains("timed out", ignoreCase = true) == true
        }
}
