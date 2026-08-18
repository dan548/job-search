package th.sibraine.jobagent.matching.infrastructure

import th.sibraine.jobagent.shared.ai.AiAnalysisException
import th.sibraine.jobagent.shared.ai.OpenAiResponsesClient
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.json.JsonCompareMode
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.content
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient
import java.io.IOException
import th.sibraine.jobagent.shared.ai.AiRequestTimeoutException

class OpenAiResponsesClientTest {
    private val objectMapper = jacksonObjectMapper()

    @Test
    fun `sends strict non-stored request and extracts output text`() {
        val builder = RestClient.builder().baseUrl("https://api.openai.test")
        val server = MockRestServiceServer.bindTo(builder).build()
        server.expect(requestTo("https://api.openai.test/v1/responses"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(
                content().json(
                    """{
                      "model":"test-model",
                      "store":false,
                      "text":{"format":{"type":"json_schema","name":"vacancy_analysis","strict":true}}
                    }""",
                    JsonCompareMode.LENIENT,
                )
            )
            .andRespond(
                withSuccess(
                    """{
                      "status":"completed",
                      "output":[{"type":"message","content":[
                        {"type":"output_text","text":"{\"role\":\"Engineer\"}"}
                      ]}]
                    }""",
                    MediaType.APPLICATION_JSON,
                )
            )
        val client = OpenAiResponsesClient(builder.build(), objectMapper, "test-model")

        val output = client.generate("system", "input", "vacancy_analysis", mapOf("type" to "object"))

        assertEquals("{\"role\":\"Engineer\"}", output)
        server.verify()
    }

    @Test
    fun `turns refusal into analysis error`() {
        val builder = RestClient.builder().baseUrl("https://api.openai.test")
        val server = MockRestServiceServer.bindTo(builder).build()
        server.expect(requestTo("https://api.openai.test/v1/responses"))
            .andRespond(
                withSuccess(
                    """{
                      "status":"completed",
                      "output":[{"type":"message","content":[
                        {"type":"refusal","refusal":"Cannot process this input"}
                      ]}]
                    }""",
                    MediaType.APPLICATION_JSON,
                )
            )
        val client = OpenAiResponsesClient(builder.build(), objectMapper, "test-model")

        assertThrows(AiAnalysisException::class.java) {
            client.generate("system", "input", "vacancy_analysis", mapOf("type" to "object"))
        }
        server.verify()
    }

    @Test
    fun `reports a request timeout separately`() {
        val builder = RestClient.builder().baseUrl("https://api.openai.test")
        val server = MockRestServiceServer.bindTo(builder).build()
        server.expect(requestTo("https://api.openai.test/v1/responses"))
            .andRespond { throw ResourceAccessException("I/O error", IOException("Operation timed out")) }
        val client = OpenAiResponsesClient(builder.build(), objectMapper, "test-model")

        assertThrows(AiRequestTimeoutException::class.java) {
            client.generate("system", "input", "vacancy_analysis", mapOf("type" to "object"))
        }
        server.verify()
    }
}
