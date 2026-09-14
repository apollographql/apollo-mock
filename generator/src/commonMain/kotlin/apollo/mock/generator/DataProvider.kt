package apollo.mock.generator

import kotlinx.serialization.json.JsonObject

/**
 * A backend capable of generating a JSON response constrained to a given schema.
 */
internal interface DataProvider : AutoCloseable {
  /**
   * [count] is the number of entities [promptText] asks for; it's redundant with [promptText]
   * (which already spells it out for the LLM) but is passed separately so a provider doesn't
   * have to parse it back out of the prompt.
   */
  suspend fun chat(promptText: String, schema: JsonObject, count: Int): String
}