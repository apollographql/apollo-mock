package apollo.mock.generator

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
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
 * Minimal client for the Anthropic Messages API, using structured output
 * (`output_config.format`) to constrain responses to a JSON schema.
 */
internal class AnthropicClient(private val apiKey: String, private val model: String) : DataProvider {
  private val httpClient = HttpClient(CIO) {
    install(HttpTimeout) {
      requestTimeoutMillis = 5 * 60 * 1000
    }
  }

  override suspend fun chat(promptText: String, schema: JsonObject, count: Int): String {
    val requestBody = buildJsonObject {
      put("model", model)
      put("max_tokens", 8192)
      putJsonArray("messages") {
        addJsonObject {
          put("role", "user")
          put("content", promptText)
        }
      }
      putJsonObject("output_config") {
        putJsonObject("format") {
          put("type", "json_schema")
          put("schema", schema)
        }
      }
    }

    val response = httpClient.post("https://api.anthropic.com/v1/messages") {
      contentType(ContentType.Application.Json)
      header("x-api-key", apiKey)
      header("anthropic-version", "2023-06-01")
      setBody(requestBody.toString())
    }
    val responseBody = response.bodyAsText()
    val root = Json.parseToJsonElement(responseBody).jsonObject
    if (root["error"] != null) {
      error("Anthropic API error:\n$responseBody")
    }
    val content = root["content"]?.jsonArray ?: error("No 'content' in Anthropic response:\n$responseBody")
    val text = content.firstOrNull { it.jsonObject["type"]?.jsonPrimitive?.content == "text" }
        ?.jsonObject?.get("text")?.jsonPrimitive?.content
    return text ?: error("No text content in Anthropic response:\n$responseBody")
  }

  override fun close() {
    httpClient.close()
  }
}