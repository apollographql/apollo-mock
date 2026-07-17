package azir.generator

import com.apollographql.apollo.ast.GQLEnumTypeDefinition
import com.apollographql.apollo.ast.GQLListType
import com.apollographql.apollo.ast.GQLNamedType
import com.apollographql.apollo.ast.GQLNonNullType
import com.apollographql.apollo.ast.GQLObjectTypeDefinition
import com.apollographql.apollo.ast.GQLScalarTypeDefinition
import com.apollographql.apollo.ast.GQLType
import com.apollographql.apollo.ast.Schema
import com.apollographql.apollo.ast.pretty
import com.apollographql.apollo.ast.toGQLDocument
import com.apollographql.apollo.ast.toSchema
import java.io.File
import kotlin.system.exitProcess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray

private const val USAGE = """
Generates fake entities for a GraphQL schema using an LLM (through langchain4j).

Usage: generator --schema <schema.graphqls> --output <directory> [--provider <provider>] [--count <n>] [--model <model-name>] [--base-url <url>]

  --schema    path to the GraphQL schema file (SDL)
  --output    directory where the JSON files are written (one file per type)
  --provider  LLM provider: 'anthropic' or 'ollama' (default: anthropic)
  --count     number of entities to generate per type (default: 8)
  --model     model name (default: claude-opus-4-8 for anthropic, llama3.2 for ollama)
  --base-url  Ollama base url (default: http://localhost:11434, ollama only)

The ANTHROPIC_API_KEY environment variable must be set when using the anthropic provider.
"""

fun main(args: Array<String>) {
  var schemaPath: String? = null
  var outputPath: String? = null
  var provider = "anthropic"
  var count = 8
  var modelName: String? = null
  var baseUrl: String? = null

  var i = 0
  while (i < args.size) {
    when (args[i]) {
      "--schema" -> schemaPath = args[++i]
      "--output" -> outputPath = args[++i]
      "--provider" -> provider = args[++i]
      "--count" -> count = args[++i].toInt()
      "--model" -> modelName = args[++i]
      "--base-url" -> baseUrl = args[++i]
      else -> {
        println(USAGE)
        exitProcess(1)
      }
    }
    i++
  }

  if (schemaPath == null || outputPath == null) {
    println(USAGE)
    exitProcess(1)
  }

  val schema = File(schemaPath).readText().toGQLDocument().toSchema()
  val outputDir = File(outputPath)
  outputDir.mkdirs()

  val generator = Generator(schema, chatModel(provider, modelName, baseUrl))
  val entities = generator.generateEntities(count)
  linkEntities(schema, entities)

  val json = Json { prettyPrint = true }
  entities.forEach { (typeName, list) ->
    val file = File(outputDir, "$typeName.json")
    file.writeText(json.encodeToString(JsonArray.serializer(), JsonArray(list)))
    println("Wrote ${list.size} entities to $file")
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

internal fun chatModel(provider: String, modelName: String?, baseUrl: String?): dev.langchain4j.model.chat.ChatModel {
  return when (provider) {
    "anthropic" -> {
      check(baseUrl == null) { "--base-url is only supported with the ollama provider" }
      dev.langchain4j.model.anthropic.AnthropicChatModel.builder()
        .apiKey(System.getenv("ANTHROPIC_API_KEY") ?: error("Set the ANTHROPIC_API_KEY environment variable"))
        .modelName(modelName ?: "claude-opus-4-8")
        .maxTokens(16000)
        .build()
    }

    "ollama" -> {
      dev.langchain4j.model.ollama.OllamaChatModel.builder()
        .baseUrl(baseUrl ?: "http://localhost:11434")
        .modelName(modelName ?: "llama3.2")
        .numPredict(16000)
        .build()
    }

    else -> error("Unknown provider: '$provider'. Supported providers: anthropic, ollama")
  }
}

internal class Generator(private val schema: Schema, private val chatModel: dev.langchain4j.model.chat.ChatModel) {
  private val rootTypeNames = listOf("query", "mutation", "subscription")
    .mapNotNull { schema.rootTypeNameOrNullFor(it) }
    .toSet()

  /**
   * First pass: for each entity type, ask the LLM to generate fake values for the
   * leaf (scalar and enum) fields. Composite fields are linked in a second pass.
   */
  fun generateEntities(count: Int): Map<String, MutableList<JsonObject>> {
    val entityTypes = schema.typeDefinitions.values
      .filterIsInstance<GQLObjectTypeDefinition>()
      .filter { !it.name.startsWith("__") && it.name !in rootTypeNames }

    return entityTypes.associate { type ->
      println("Generating ${type.name}...")
      type.name to generate(type, count).toMutableList()
    }
  }

  private fun generate(type: GQLObjectTypeDefinition, count: Int): List<JsonObject> {
    val leafFields = type.fields.filter { schema.typeDefinitions[it.type.rawTypeName()].isLeaf() }
    val fieldsSdl = leafFields.joinToString("\n") { "  ${it.name}: ${it.type.pretty()}" }
    val enums = leafFields.mapNotNull { schema.typeDefinitions[it.type.rawTypeName()] as? GQLEnumTypeDefinition }
      .distinctBy { it.name }
      .joinToString("\n") { enum -> "- ${enum.name}: one of ${enum.enumValues.joinToString(", ") { it.name }}" }

    val prompt = buildString {
      appendLine("You generate fake test data for a GraphQL API.")
      appendLine("Generate exactly $count diverse and realistic entities for this GraphQL type:")
      appendLine()
      appendLine("type ${type.name} {")
      appendLine(fieldsSdl)
      appendLine("}")
      if (enums.isNotBlank()) {
        appendLine()
        appendLine("Enum fields must use one of their allowed values:")
        appendLine(enums)
      }
      appendLine()
      appendLine("Respond with a JSON array of $count objects and nothing else: no markdown fences, no commentary.")
      appendLine("Each object must contain exactly the fields listed above, with values matching their GraphQL types.")
    }

    val response = chatModel.chat(prompt)
    val objects = response.extractJsonArray().map { it as JsonObject }

    val hasId = type.fields.any { it.name == "id" }
    return objects.mapIndexed { index, obj ->
      if (hasId) {
        JsonObject(obj + ("id" to JsonPrimitive("${type.name.lowercase()}-${index + 1}")))
      } else {
        obj
      }
    }
  }
}

/**
 * Extracts the JSON array from the model response, tolerating markdown fences and
 * other text around it (thinking preamble, commentary, etc.).
 */
private fun String.extractJsonArray(): JsonArray {
  val start = indexOf('[')
  val end = lastIndexOf(']')
  check(start != -1 && end > start) { "No JSON array found in model response:\n$this" }
  return Json.parseToJsonElement(substring(start, end + 1)).jsonArray
}

private fun com.apollographql.apollo.ast.GQLTypeDefinition?.isLeaf(): Boolean {
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
    is com.apollographql.apollo.ast.GQLInterfaceTypeDefinition,
    is com.apollographql.apollo.ast.GQLUnionTypeDefinition,
      -> possibleTypes(typeName).toList()

    else -> emptyList()
  }
}
