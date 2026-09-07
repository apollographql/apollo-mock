package apollo.mock.generator

import kotlin.random.Random
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * A [DataProvider] that never calls out to a real LLM: it fills the requested schema with
 * random data (lorem ipsum for strings, a random pick for enums, random numbers/booleans) so the
 * rest of the pipeline - linking, serving, the merge-on-regenerate logic in [Generator] - can be
 * exercised quickly and without network access or an API key.
 */
internal class RandomDataProvider(seed: Long? = null) : DataProvider {
  private val random = seed?.let { Random(it) } ?: Random.Default

  override suspend fun chat(promptText: String, schema: JsonObject, count: Int): String {
    val itemSchema = schema.entitySchema()

    return buildJsonObject {
      putJsonArray("entities") {
        repeat(count) { add(itemSchema.randomValue()) }
      }
    }.toString()
  }

  override fun close() = Unit

  private fun JsonObject.entitySchema(): JsonObject {
    return this["properties"]?.jsonObject?.get("entities")?.jsonObject?.get("items")?.jsonObject
        ?: error("Unexpected schema shape (expected {properties: {entities: {items: ...}}}):\n$this")
  }

  private fun JsonObject.randomValue(): JsonElement {
    val enumValues = this["enum"]?.jsonArray
    if (!enumValues.isNullOrEmpty()) {
      return enumValues[random.nextInt(enumValues.size)]
    }
    return when (val type = this["type"]?.jsonPrimitive?.content) {
      "integer" -> JsonPrimitive(random.nextInt(0, 1000))
      "number" -> JsonPrimitive(random.nextRoundedDouble(0.0, 1000.0))
      "boolean" -> JsonPrimitive(random.nextBoolean())
      "string" -> JsonPrimitive(random.loremPhrase())
      "array" -> {
        val itemSchema = this["items"]?.jsonObject ?: error("Array schema missing 'items':\n$this")
        JsonArray(List(random.nextInt(1, 4)) { itemSchema.randomValue() })
      }
      "object" -> {
        val properties = this["properties"]?.jsonObject.orEmpty()
        JsonObject(properties.mapValues { (_, fieldSchema) -> fieldSchema.jsonObject.randomValue() })
      }
      else -> error("Unsupported schema type '$type' in:\n$this")
    }
  }

  private fun Random.nextRoundedDouble(from: Double, until: Double): Double {
    return kotlin.math.round(nextDouble(from, until) * 100) / 100
  }

  private fun Random.loremPhrase(minWords: Int = 3, maxWords: Int = 8): String {
    val words = List(nextInt(minWords, maxWords + 1)) { LOREM_WORDS[nextInt(LOREM_WORDS.size)] }
    return words.joinToString(" ").replaceFirstChar { it.uppercase() }
  }

  companion object {
    private val LOREM_WORDS = listOf(
        "lorem", "ipsum", "dolor", "sit", "amet", "consectetur", "adipiscing", "elit", "sed", "do",
        "eiusmod", "tempor", "incididunt", "ut", "labore", "et", "dolore", "magna", "aliqua", "enim",
        "ad", "minim", "veniam", "quis", "nostrud", "exercitation", "ullamco", "laboris", "nisi",
        "aliquip", "ex", "ea", "commodo", "consequat", "duis", "aute", "irure", "in", "reprehenderit",
        "voluptate", "velit", "esse", "cillum", "fugiat", "nulla", "pariatur", "excepteur", "sint",
        "occaecat", "cupidatat", "non", "proident", "sunt", "culpa", "qui", "officia", "deserunt",
        "mollit", "anim", "id", "est", "laborum",
    )
  }
}
