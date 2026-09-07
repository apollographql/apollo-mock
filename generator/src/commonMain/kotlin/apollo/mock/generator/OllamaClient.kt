package apollo.mock.generator

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
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

/**
 * Minimal client for the Ollama `/api/chat` endpoint, used with structured output
 * (the `format` field) to constrain responses to a JSON schema.
 */
internal class OllamaClient(private val baseUrl: String, private val model: String) : DataProvider {
  private val httpClient = HttpClient(CIO) {
    install(HttpTimeout) {
      // Local models can take a while to generate a schema-constrained response
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
      put("format", schema)
      put("stream", false)
    }

    val response = httpClient.post("$baseUrl/api/chat") {
      contentType(ContentType.Application.Json)
      setBody(requestBody.toString())
    }
    val responseBody = response.bodyAsText()
    val message = Json.parseToJsonElement(responseBody).jsonObject["message"]?.jsonObject
        ?: error("No 'message' in Ollama response:\n$responseBody")
    return message["content"]?.jsonPrimitive?.content
        ?: error("No 'content' in Ollama response:\n$responseBody")
  }

  override fun close() {
    httpClient.close()
  }
}

/**
 * Returns the name of the first model pulled locally, queried from Ollama's `/api/tags`
 * endpoint, so `generate()` doesn't have to hardcode a model that might not be installed.
 */
internal suspend fun defaultOllamaModel(baseUrl: String): String {
  val httpClient = HttpClient(CIO)
  val name = try {
    val responseBody = httpClient.get("$baseUrl/api/tags").bodyAsText()
    Json.parseToJsonElement(responseBody).jsonObject["models"]?.jsonArray
        ?.firstOrNull()?.jsonObject?.get("name")?.jsonPrimitive?.content
  } finally {
    httpClient.close()
  }
  return requireNotNull(name) {
    "No local Ollama model found at '$baseUrl'. Pull one with 'ollama pull <model>' or pass --model explicitly."
  }
}