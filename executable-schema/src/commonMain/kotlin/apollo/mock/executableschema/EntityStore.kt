package apollo.mock.executableschema

import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Holds the fake entities loaded from the JSON files written by the generator:
 * one `entity/<TypeName>/<id>.json` file per entity.
 */
class EntityStore(
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
    fun load(dir: Path): EntityStore {
      val entityDir = Path(dir, "entity")
      check(SystemFileSystem.metadataOrNull(entityDir)?.isDirectory == true) { "'$entityDir' is not a directory. Run the generator first." }

      val entities = SystemFileSystem.list(entityDir)
          .filter { SystemFileSystem.metadataOrNull(it)?.isDirectory == true }
          .associate { typeDir ->
            val typeName = typeDir.name
            val list = SystemFileSystem.list(typeDir).filter { it.name.substringAfterLast('.', "") == "json" }.map { file ->
              val text = SystemFileSystem.source(file).buffered().use { it.readString() }
              @Suppress("UNCHECKED_CAST")
              val entity = Json.parseToJsonElement(text).toKotlin() as Map<String, Any?>
              // Make sure every entity knows its concrete type for abstract type resolution
              entity + ("__typename" to typeName)
            }
            println("Loaded ${list.size} $typeName entities from $typeDir")
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
