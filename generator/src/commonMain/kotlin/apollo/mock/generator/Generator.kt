package apollo.mock.generator

import com.apollographql.apollo.ast.GQLEnumTypeDefinition
import com.apollographql.apollo.ast.GQLFieldDefinition
import com.apollographql.apollo.ast.GQLInterfaceTypeDefinition
import com.apollographql.apollo.ast.GQLListType
import com.apollographql.apollo.ast.GQLNamedType
import com.apollographql.apollo.ast.GQLNonNullType
import com.apollographql.apollo.ast.GQLObjectTypeDefinition
import com.apollographql.apollo.ast.GQLScalarTypeDefinition
import com.apollographql.apollo.ast.GQLType
import com.apollographql.apollo.ast.GQLTypeDefinition
import com.apollographql.apollo.ast.GQLUnionTypeDefinition
import com.apollographql.apollo.ast.Schema
import com.apollographql.apollo.ast.pretty
import com.apollographql.apollo.ast.toGQLDocument
import com.apollographql.apollo.ast.toSchema
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Generates fake data for a GraphQL [schema] and its [operations] using an LLM served by
 * Ollama, the Anthropic API, or the Vercel AI Gateway, and writes:
 * - `entity/<TypeName>/<id>.json`: one file per schema-based fake entity
 * - `operation/<operationName>.json`: one file per operation using the `@mock` directive,
 *   mapping the GraphQL path in the document (the empty string for the root) to the mocked value
 *
 * to [outputPath].
 */
suspend fun generate(
  schema: String,
  operations: Sequence<String>,
  outputPath: String,
  provider: String = "ollama",
  count: Int = 8,
  model: String? = null,
  baseUrl: String? = null,
  apiKey: String? = null,
) {
  require(provider == "ollama" || provider == "anthropic" || provider == "vercel" || provider == "random") {
    "Unknown provider: '$provider'. Supported providers: ollama, anthropic, vercel, random"
  }

  val schemaDocument = schema.toGQLDocument()
  val allDocuments = listOf(schemaDocument) + operations.map { it.toGQLDocument() }
  val gqlSchema = schemaDocument.toSchema()

  val outputDir = Path(outputPath)
  val entityDir = Path(outputDir, "entity")
  val operationDir = Path(outputDir, "operation")
  SystemFileSystem.createDirectories(entityDir)
  SystemFileSystem.createDirectories(operationDir)
  val existingEntities = loadExistingEntities(entityDir)

  val client: DataProvider = when (provider) {
    "ollama" -> {
      val ollamaBaseUrl = baseUrl ?: "http://localhost:11434"
      OllamaClient(ollamaBaseUrl, model ?: defaultOllamaModel(ollamaBaseUrl))
    }

    "anthropic" -> {
      requireNotNull(apiKey) {
        "provider 'anthropic' requires an API key: pass --api-key or set the ANTHROPIC_API_KEY environment variable"
      }
      AnthropicClient(apiKey, model ?: "claude-haiku-4-5")
    }

    "vercel" -> {
      requireNotNull(apiKey) {
        "provider 'vercel' requires an API key: pass --api-key or set the AI_GATEWAY_API_KEY environment variable"
      }
      VercelAiGatewayClient(apiKey, model ?: "anthropic/claude-haiku-4.5", baseUrl ?: "https://ai-gateway.vercel.sh/v1")
    }

    "random" -> RandomDataProvider()
    else -> error("Unknown provider: '$provider'")
  }
  try {
    val generator = Generator(gqlSchema, client)
    val entities = generator.generateEntities(count, existingEntities)
    linkEntities(gqlSchema, entities)

    val json = Json { prettyPrint = true }

    clearDirectory(entityDir)
    entities.forEach { (typeName, list) ->
      val typeDir = Path(entityDir, typeName)
      SystemFileSystem.createDirectories(typeDir)
      list.forEachIndexed { index, entity ->
        val key = (entity["id"] as? JsonPrimitive)?.content ?: "${typeName.lowercase()}-${index + 1}"
        val file = Path(typeDir, "$key.json")
        SystemFileSystem.sink(file).buffered()
          .use { it.writeString(json.encodeToString(JsonObject.serializer(), entity)) }
      }
      println("Wrote ${list.size} $typeName entities to $typeDir")
    }

    val operationMocks = generateOperationMocks(gqlSchema, client, allDocuments)
    clearDirectory(operationDir)
    operationMocks.forEach { (operationName, mocks) ->
      val file = Path(operationDir, "$operationName.json")
      SystemFileSystem.sink(file).buffered().use { it.writeString(json.encodeToString(JsonObject.serializer(), mocks)) }
      println("Wrote ${mocks.size} mock(s) for operation '$operationName' to $file")
    }
  } finally {
    client.close()
  }
}

/**
 * Loads the entities written by a previous run of the generator, keyed by type name, so that
 * unchanged data can be kept stable across schema changes instead of being fully regenerated.
 */
private fun loadExistingEntities(entityDir: Path): Map<String, List<JsonObject>> {
  if (SystemFileSystem.metadataOrNull(entityDir)?.isDirectory != true) return emptyMap()

  return SystemFileSystem.list(entityDir)
    .filter { SystemFileSystem.metadataOrNull(it)?.isDirectory == true }
    .associate { typeDir ->
      val list = SystemFileSystem.list(typeDir)
        .filter { it.name.substringAfterLast('.', "") == "json" }
        .sortedWith(compareBy({ it.naturalSortKey().first }, { it.naturalSortKey().second }))
        .map { file ->
          val text = SystemFileSystem.source(file).buffered().use { it.readString() }
          Json.parseToJsonElement(text) as JsonObject
        }
      typeDir.name to list
    }
}

/**
 * A sort key that orders `product-2` before `product-10`, unlike plain lexicographic order.
 */
private fun Path.naturalSortKey(): Pair<String, Long> {
  val stem = name.substringBeforeLast('.')
  val digits = stem.takeLastWhile { it.isDigit() }
  return stem.dropLast(digits.length) to (digits.toLongOrNull() ?: -1L)
}

/**
 * Deletes the contents of [dir] (recursively), keeping [dir] itself, so a run that removes
 * entities/operations doesn't leave stale files behind.
 */
private fun clearDirectory(dir: Path) {
  if (SystemFileSystem.metadataOrNull(dir)?.isDirectory != true) return
  SystemFileSystem.list(dir).forEach { entry ->
    if (SystemFileSystem.metadataOrNull(entry)?.isDirectory == true) {
      clearDirectory(entry)
    }
    SystemFileSystem.delete(entry)
  }
}

/**
 * Second pass: fields whose type is an object/interface/union are linked to
 * previously generated entities using `{__typename, id}` references. The server
 * resolves those references against the full entities at execution time.
 */
private fun linkEntities(schema: Schema, entities: Map<String, MutableList<JsonObject>>) {
  entities.forEach { (typeName, list) ->
    val typeDefinition = schema.typeDefinitions[typeName] as GQLObjectTypeDefinition
    val compositeFields = typeDefinition.fields.mapNotNull { field ->
      val candidates = schema.concreteTypes(field.type.rawTypeName()).filter { !entities[it].isNullOrEmpty() }
      if (candidates.isEmpty()) null else Triple(field.name, field.type.isList(), candidates)
    }
    if (compositeFields.isEmpty()) {
      return@forEach
    }

    list.forEachIndexed { index, entity ->
      val linkedFields = compositeFields.withIndex().associate { (fieldOrdinal, field) ->
        val (fieldName, isList, candidates) = field
        val references = List(if (isList) 3 else 1) { position ->
          // Deterministic but different per entity and per field so that
          // entities don't all point to the same targets
          val offset = index + fieldOrdinal + position
          val target = candidates[offset % candidates.size]
          val targetEntities = entities.getValue(target)
          reference(target, targetEntities[offset % targetEntities.size])
        }
        fieldName to if (isList) JsonArray(references.distinct()) else references.first()
      }
      list[index] = JsonObject(entity + linkedFields)
    }
  }
}

private fun reference(typeName: String, entity: JsonObject): JsonObject {
  val id = entity["id"]
  return if (id != null) {
    JsonObject(mapOf("__typename" to JsonPrimitive(typeName), "id" to id))
  } else {
    // No id to reference: embed a copy of the entity
    JsonObject(entity + ("__typename" to JsonPrimitive(typeName)))
  }
}

internal class Generator(
  private val schema: Schema,
  private val client: DataProvider,
) {
  private val rootTypeNames = listOf("query", "mutation", "subscription")
    .mapNotNull { schema.rootTypeNameOrNullFor(it) }
    .toSet()

  /**
   * First pass: for each entity type, ask the LLM to generate fake values for the
   * leaf (scalar and enum) fields. Composite fields are linked in a second pass.
   */
  suspend fun generateEntities(
    count: Int,
    existingEntities: Map<String, List<JsonObject>>
  ): Map<String, MutableList<JsonObject>> {
    val entityTypes = schema.typeDefinitions.values
      .filterIsInstance<GQLObjectTypeDefinition>()
      .filter { !it.name.startsWith("__") && it.name !in rootTypeNames }

    return entityTypes.associate { type ->
      println("Generating ${type.name}...")
      type.name to generate(type, count, existingEntities[type.name].orEmpty()).toMutableList()
    }
  }

  /**
   * Reuses as much of [existing] as still fits the current schema: fields no longer defined are
   * dropped (composite/linked fields are always dropped, since they're recomputed in the linking
   * pass anyway), and the LLM is only asked to fill in newly added leaf fields or entities missing
   * to reach [count]. Existing `id`s are kept so unrelated data stays stable across runs.
   */
  private suspend fun generate(
    type: GQLObjectTypeDefinition,
    count: Int,
    existing: List<JsonObject>
  ): List<JsonObject> {
    val hasId = type.fields.any { it.name == "id" }
    // 'id' is never asked from the LLM: it's assigned deterministically below so that it's
    // guaranteed unique and stable across runs, regardless of what the LLM would come up with.
    val leafFields = type.fields.filter { it.name != "id" && schema.typeDefinitions[it.type.rawTypeName()].isLeaf() }

    val keptKeys = leafFields.mapTo(mutableSetOf()) { it.name }.apply { if (hasId) add("id") }
    val stable = existing.take(count).map { entity -> JsonObject(entity.filterKeys { it in keptKeys }) }

    val missingFields = leafFields.filter { field -> stable.any { field.name !in it } }
    val reused = if (stable.isNotEmpty() && missingFields.isNotEmpty()) {
      val additions = generateLeafValues(type, missingFields, stable.size)
      stable.mapIndexed { index, entity -> JsonObject(entity + additions[index]) }
    } else {
      stable
    }

    val missingCount = count - reused.size
    val fresh = if (missingCount > 0) generateLeafValues(type, leafFields, missingCount) else emptyList()

    return (reused + fresh).mapIndexed { index, obj ->
      if (hasId && obj["id"] == null) {
        JsonObject(obj + ("id" to JsonPrimitive("${type.name.lowercase()}-${index + 1}")))
      } else {
        obj
      }
    }
  }

  private suspend fun generateLeafValues(
    type: GQLObjectTypeDefinition,
    fields: List<GQLFieldDefinition>,
    count: Int
  ): List<JsonObject> {
    if (fields.isEmpty()) return List(count) { JsonObject(emptyMap()) }

    val promptText = buildString {
      appendLine("You generate fake test data for a GraphQL API.")
      appendLine("Generate exactly $count diverse and realistic entities for this GraphQL type:")
      appendLine()
      appendLine("type ${type.name} {")
      appendLine(fields.joinToString("\n") { "  ${it.name}: ${it.type.pretty()}" })
      appendLine("}")
    }

    val content = client.chat(promptText, jsonSchema(fields), count)
    return content.parseEntities().map { it as JsonObject }
  }

  /**
   * Builds a JSON schema used as structured output so that the model can only produce
   * entities matching the GraphQL type.
   */
  private fun jsonSchema(fields: List<GQLFieldDefinition>): JsonObject {
    val entitySchema = buildJsonObject {
      put("type", "object")
      putJsonObject("properties") {
        fields.forEach { put(it.name, it.type.toJsonSchemaElement(schema)) }
      }
      putJsonArray("required") { fields.forEach { add(it.name) } }
      put("additionalProperties", false)
    }
    return wrapAsEntitiesArraySchema(entitySchema)
  }
}

/**
 * Wraps [itemSchema] into `{"entities": [itemSchema, ...]}`: providers don't always accept an
 * array as the root element, and this convention is shared by every value the generator asks a
 * [DataProvider] for, whether it's a batch of leaf-field entities or a single `@mock` hint value.
 */
internal fun wrapAsEntitiesArraySchema(itemSchema: JsonObject): JsonObject {
  return buildJsonObject {
    put("type", "object")
    putJsonObject("properties") {
      putJsonObject("entities") {
        put("type", "array")
        put("items", itemSchema)
      }
    }
    putJsonArray("required") { add("entities") }
    put("additionalProperties", false)
  }
}

internal fun GQLType.toJsonSchemaElement(schema: Schema): JsonObject {
  return when (this) {
    is GQLNonNullType -> type.toJsonSchemaElement(schema)
    is GQLListType -> buildJsonObject {
      put("type", "array")
      put("items", type.toJsonSchemaElement(schema))
    }

    is GQLNamedType -> when (name) {
      "Int" -> buildJsonObject { put("type", "integer") }
      "Float" -> buildJsonObject { put("type", "number") }
      "Boolean" -> buildJsonObject { put("type", "boolean") }
      else -> {
        val definition = schema.typeDefinitions[name]
        if (definition is GQLEnumTypeDefinition) {
          buildJsonObject {
            put("type", "string")
            putJsonArray("enum") { definition.enumValues.forEach { add(it.name) } }
          }
        } else {
          // String, ID and custom scalars
          buildJsonObject { put("type", "string") }
        }
      }
    }
  }
}

internal fun String.parseEntities(): JsonArray {
  return when (val element = Json.parseToJsonElement(trim())) {
    is JsonArray -> element
    is JsonObject -> element["entities"]?.jsonArray ?: error("No 'entities' array in model response:\n$this")
    else -> error("Unexpected model response:\n$this")
  }
}

private fun GQLTypeDefinition?.isLeaf(): Boolean {
  return this is GQLScalarTypeDefinition || this is GQLEnumTypeDefinition
}

internal fun GQLType.rawTypeName(): String = when (this) {
  is GQLNonNullType -> type.rawTypeName()
  is GQLListType -> type.rawTypeName()
  is GQLNamedType -> name
}

internal fun GQLType.isList(): Boolean = when (this) {
  is GQLNonNullType -> type.isList()
  is GQLListType -> true
  is GQLNamedType -> false
}

internal fun Schema.concreteTypes(typeName: String): List<String> {
  return when (typeDefinitions[typeName]) {
    is GQLObjectTypeDefinition -> listOf(typeName)
    is GQLInterfaceTypeDefinition,
    is GQLUnionTypeDefinition,
      -> possibleTypes(typeName).toList()

    else -> emptyList()
  }
}
