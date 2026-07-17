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
import dev.langchain4j.data.message.UserMessage
import dev.langchain4j.model.anthropic.AnthropicChatModel
import dev.langchain4j.model.chat.ChatModel
import dev.langchain4j.model.chat.request.ChatRequest
import dev.langchain4j.model.chat.request.ResponseFormat
import dev.langchain4j.model.chat.request.ResponseFormatType
import dev.langchain4j.model.chat.request.json.JsonArraySchema
import dev.langchain4j.model.chat.request.json.JsonBooleanSchema
import dev.langchain4j.model.chat.request.json.JsonEnumSchema
import dev.langchain4j.model.chat.request.json.JsonIntegerSchema
import dev.langchain4j.model.chat.request.json.JsonNumberSchema
import dev.langchain4j.model.chat.request.json.JsonObjectSchema
import dev.langchain4j.model.chat.request.json.JsonSchema
import dev.langchain4j.model.chat.request.json.JsonSchemaElement
import dev.langchain4j.model.chat.request.json.JsonStringSchema
import dev.langchain4j.model.ollama.OllamaChatModel
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

internal fun chatModel(provider: String, modelName: String?, baseUrl: String?): ChatModel {
  return when (provider) {
    "anthropic" -> {
      check(baseUrl == null) { "--base-url is only supported with the ollama provider" }
      AnthropicChatModel.builder()
        .apiKey(System.getenv("ANTHROPIC_API_KEY") ?: error("Set the ANTHROPIC_API_KEY environment variable"))
        .modelName(modelName ?: "claude-opus-4-8")
        .maxTokens(16000)
        .build()
    }

    "ollama" -> {
      OllamaChatModel.builder()
        .baseUrl(baseUrl ?: "http://localhost:11434")
        .modelName(modelName ?: "llama3.2")
        .numPredict(16000)
        .build()
    }

    else -> error("Unknown provider: '$provider'. Supported providers: anthropic, ollama")
  }
}

internal class Generator(private val schema: Schema, private val chatModel: ChatModel) {
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

    val prompt = buildString {
      appendLine("You generate fake test data for a GraphQL API.")
      appendLine("Generate exactly $count diverse and realistic entities for this GraphQL type:")
      appendLine()
      appendLine("type ${type.name} {")
      appendLine(leafFields.joinToString("\n") { "  ${it.name}: ${it.type.pretty()}" })
      appendLine("}")
    }

    val request = ChatRequest.builder()
      .messages(UserMessage.from(prompt))
      .responseFormat(
          ResponseFormat.builder()
            .type(ResponseFormatType.JSON)
            .jsonSchema(jsonSchema(type.name, leafFields))
            .build()
      )
      .build()

    val response = chatModel.chat(request).aiMessage().text()
    val objects = response.parseEntities().map { it as JsonObject }

    val hasId = type.fields.any { it.name == "id" }
    return objects.mapIndexed { index, obj ->
      if (hasId) {
        JsonObject(obj + ("id" to JsonPrimitive("${type.name.lowercase()}-${index + 1}")))
      } else {
        obj
      }
    }
  }

  /**
   * Builds a JSON schema used as structured output so that the model can only produce
   * entities matching the GraphQL type. Providers don't always accept an array as the
   * root element so the entities are wrapped in an object.
   */
  private fun jsonSchema(typeName: String, fields: List<GQLFieldDefinition>): JsonSchema {
    val entitySchema = JsonObjectSchema.builder()
      .apply { fields.forEach { addProperty(it.name, it.type.toJsonSchemaElement()) } }
      .required(fields.map { it.name })
      .build()

    return JsonSchema.builder()
      .name("${typeName}Entities")
      .rootElement(
          JsonObjectSchema.builder()
            .addProperty("entities", JsonArraySchema.builder().items(entitySchema).build())
            .required("entities")
            .build()
      )
      .build()
  }

  private fun GQLType.toJsonSchemaElement(): JsonSchemaElement {
    return when (this) {
      is GQLNonNullType -> type.toJsonSchemaElement()
      is GQLListType -> JsonArraySchema.builder().items(type.toJsonSchemaElement()).build()
      is GQLNamedType -> when (name) {
        "Int" -> JsonIntegerSchema.builder().build()
        "Float" -> JsonNumberSchema.builder().build()
        "Boolean" -> JsonBooleanSchema.builder().build()
        else -> {
          val definition = schema.typeDefinitions[name]
          if (definition is GQLEnumTypeDefinition) {
            JsonEnumSchema.builder().enumValues(definition.enumValues.map { it.name }).build()
          } else {
            // String, ID and custom scalars
            JsonStringSchema.builder().build()
          }
        }
      }
    }
  }
}

private fun String.parseEntities(): JsonArray {
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
