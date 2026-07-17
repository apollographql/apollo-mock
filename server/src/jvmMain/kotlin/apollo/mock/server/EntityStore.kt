package apollo.mock.server

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.longOrNull

/**
 * Holds the fake entities loaded from the JSON files written by the generator:
 * one `<TypeName>.json` file per type, each containing an array of objects.
 */
internal class EntityStore(
    private val entities: Map<String, List<Map<String, Any?>>>,
) {
  fun all(typeNames: Collection<String>): List<Map<String, Any?>> {
    return typeNames.flatMap { entities[it].orEmpty() }
  }

  fun byId(typeNames: Collection<String>, id: Any?): Map<String, Any?>? {
    return all(typeNames).firstOrNull { it["id"] == id }
  }

  fun contains(typeNames: Collection<String>): Boolean {
    return typeNames.any { it in entities }
  }

  companion object {
    fun load(dir: File): EntityStore {
      check(dir.isDirectory) { "'$dir' is not a directory. Run the generator first." }

      val entities = dir.listFiles { file -> file.extension == "json" }.orEmpty().associate { file ->
        val typeName = file.nameWithoutExtension
        val list = Json.parseToJsonElement(file.readText()).jsonArray.map { element ->
          @Suppress("UNCHECKED_CAST")
          val entity = element.toKotlin() as Map<String, Any?>
          // Make sure every entity knows its concrete type for abstract type resolution
          entity + ("__typename" to typeName)
        }
        println("Loaded ${list.size} $typeName entities from $file")
        typeName to list
      }
      return EntityStore(entities)
    }
  }
}

private fun JsonElement.toKotlin(): Any? {
  return when (this) {
    is JsonNull -> null
    is JsonPrimitive -> if (isString) {
      content
    } else {
      booleanOrNull ?: intOrNull ?: longOrNull ?: doubleOrNull ?: content
    }

    is JsonArray -> map { it.toKotlin() }
    is JsonObject -> entries.associate { it.key to it.value.toKotlin() }
  }
}
