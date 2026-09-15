package apollo.mock.generator

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Minimal client for the [Vercel AI Gateway](https://vercel.com/docs/ai-gateway)'s
 * OpenAI-compatible `/chat/completions` endpoint, using structured output
 * (`response_format.json_schema`) to constrain responses to a JSON schema.
 *
 * [model] uses the gateway's `creator/model` naming convention, e.g. `anthropic/claude-haiku-4.5`
 * or `openai/gpt-5-mini`.
 */
internal class VercelAiGatewayClient(
  private val apiKey: String,
  private val model: String,
  private val baseUrl: String,
) : DataProvider {
  private val httpClient = HttpClient(httpClientEngineFactory()) {
    install(HttpTimeout) {
      requestTimeoutMillis = 5 * 60 * 1000
    }
  }

  override suspend fun chat(promptText: String, schema: JsonObject, count: Int): String {
    val requestBody = buildJsonObject {
      put("model", model)
      putJsonArray("messages") {
        addJsonObject {
          put("role", "user")
          put("content", promptText)
        }
      }
      putJsonObject("response_format") {
        put("type", "json_schema")
        putJsonObject("json_schema") {
          put("name", "entities")
          put("schema", schema)
          put("strict", true)
        }
      }
    }

    val response = httpClient.post("$baseUrl/chat/completions") {
      contentType(ContentType.Application.Json)
      header("Authorization", "Bearer $apiKey")
      setBody(requestBody.toString())
    }
    val responseBody = response.bodyAsText()
    val root = Json.parseToJsonElement(responseBody).jsonObject
    if (root["error"] != null) {
      error("Vercel AI Gateway error:\n$responseBody")
    }
    val choices = root["choices"]?.jsonArray ?: error("No 'choices' in Vercel AI Gateway response:\n$responseBody")
    val content = choices.firstOrNull()?.jsonObject?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.content
    return content ?: error("No message content in Vercel AI Gateway response:\n$responseBody")
  }

  override fun close() {
    httpClient.close()
  }
}
