package apollo.mock.generator

import com.apollographql.apollo.ast.GQLBooleanValue
import com.apollographql.apollo.ast.GQLDirective
import com.apollographql.apollo.ast.GQLDocument
import com.apollographql.apollo.ast.GQLEnumValue
import com.apollographql.apollo.ast.GQLField
import com.apollographql.apollo.ast.GQLFloatValue
import com.apollographql.apollo.ast.GQLFragmentDefinition
import com.apollographql.apollo.ast.GQLFragmentSpread
import com.apollographql.apollo.ast.GQLInlineFragment
import com.apollographql.apollo.ast.GQLIntValue
import com.apollographql.apollo.ast.GQLListType
import com.apollographql.apollo.ast.GQLListValue
import com.apollographql.apollo.ast.GQLNamedType
import com.apollographql.apollo.ast.GQLNonNullType
import com.apollographql.apollo.ast.GQLNullValue
import com.apollographql.apollo.ast.GQLObjectValue
import com.apollographql.apollo.ast.GQLOperationDefinition
import com.apollographql.apollo.ast.GQLSelection
import com.apollographql.apollo.ast.GQLStringValue
import com.apollographql.apollo.ast.GQLType
import com.apollographql.apollo.ast.GQLTypeDefinition
import com.apollographql.apollo.ast.GQLValue
import com.apollographql.apollo.ast.GQLVariableValue
import com.apollographql.apollo.ast.Schema
import com.apollographql.apollo.ast.definitionFromScope
import com.apollographql.apollo.ast.rawType
import com.apollographql.apollo.ast.responseName
import com.apollographql.apollo.ast.rootTypeDefinition
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

private const val MOCK_DIRECTIVE_NAME = "mock"

/**
 * Processes the `@mock` directive on every named operation found in [documents],
 * returning one entry per operation that has at least one `@mock` directive: a map from the
 * GraphQL path in the document (the empty string for the operation root) to the mocked value.
 */
internal suspend fun generateOperationMocks(
    schema: Schema,
    dataProvider: DataProvider,
    documents: List<GQLDocument>,
): Map<String, JsonObject> {
  val fragments = documents.flatMap { it.definitions.filterIsInstance<GQLFragmentDefinition>() }.associateBy { it.name }
  val operations = documents.flatMap { it.definitions.filterIsInstance<GQLOperationDefinition>() }

  val seenNames = mutableSetOf<String>()
  val result = mutableMapOf<String, JsonObject>()
  operations.forEach { operation ->
    val name = operation.name
        ?: error("Anonymous operations are not supported for @mock processing, please name every operation using @mock")
    require(seenNames.add(name)) { "Duplicate operation name: '$name'" }

    val mocks = collectOperationMocks(schema, dataProvider, operation, fragments)
    if (mocks.isNotEmpty()) {
      result[name] = JsonObject(mocks)
    }
  }
  return result
}

private suspend fun collectOperationMocks(
    schema: Schema,
    dataProvider: DataProvider,
    operation: GQLOperationDefinition,
    fragments: Map<String, GQLFragmentDefinition>,
): Map<String, JsonElement> {
  val rootTypeDefinition = operation.rootTypeDefinition(schema)
      ?: error("Cannot find a root type for '${operation.operationType}' operation '${operation.name}'")

  val result = mutableMapOf<String, JsonElement>()

  operation.directives.firstOrNull { it.name == MOCK_DIRECTIVE_NAME }?.let { directive ->
    val rootType: GQLType = GQLNamedType(name = rootTypeDefinition.name)
    result[""] = resolveMockValue(directive, schema, dataProvider, rootType, operation.selections, fragments)
  }

  suspend fun walk(typeDefinition: GQLTypeDefinition, selections: List<GQLSelection>, path: String) {
    selections.forEach { selection ->
      when (selection) {
        is GQLField -> {
          val fieldDefinition = selection.definitionFromScope(schema, typeDefinition)
              ?: error("Unknown field '${selection.name}' on type '${typeDefinition.name}'")
          val fieldPath = if (path.isEmpty()) selection.responseName() else "$path.${selection.responseName()}"

          selection.directives.firstOrNull { it.name == MOCK_DIRECTIVE_NAME }?.let { directive ->
            result[fieldPath] = resolveMockValue(directive, schema, dataProvider, fieldDefinition.type, selection.selections, fragments)
          }

          if (selection.selections.isNotEmpty()) {
            walk(schema.typeDefinition(fieldDefinition.type.rawType().name), selection.selections, fieldPath)
          }
        }

        is GQLInlineFragment -> {
          val fragmentTypeDefinition = selection.typeCondition?.let { schema.typeDefinition(it.name) } ?: typeDefinition
          walk(fragmentTypeDefinition, selection.selections, path)
        }

        is GQLFragmentSpread -> {
          val fragment = fragments[selection.name] ?: error("Unknown fragment '${selection.name}'")
          walk(schema.typeDefinition(fragment.typeCondition.name), fragment.selections, path)
        }
      }
    }
  }

  walk(rootTypeDefinition, operation.selections, "")

  return result
}

/**
 * Resolves a single `@mock` directive to a JSON value: [hint] is passed to the [dataProvider] to
 * generate a value matching [type]/[selections], otherwise the `value` argument is used verbatim.
 */
private suspend fun resolveMockValue(
    directive: GQLDirective,
    schema: Schema,
    dataProvider: DataProvider,
    type: GQLType,
    selections: List<GQLSelection>,
    fragments: Map<String, GQLFragmentDefinition>,
): JsonElement {
  val hint = directive.arguments.firstOrNull { it.name == "hint" }?.value?.takeUnless { it is GQLNullValue }
  val literalValue = directive.arguments.firstOrNull { it.name == "value" }?.value

  if (hint != null) {
    val hintText = (hint as? GQLStringValue)?.value ?: error("@mock 'hint' must be a string, found: $hint")
    val targetSchema = buildValueSchema(schema, type, selections, fragments)
    val promptText = buildString {
      appendLine("You generate fake test data for a GraphQL API.")
      appendLine("Generate exactly one realistic value matching this hint: $hintText")
    }

    var lastContent = ""
    repeat(3) {
      lastContent = dataProvider.chat(promptText, wrapAsEntitiesArraySchema(targetSchema), 1)
      lastContent.parseEntities().firstOrNull()?.let { return it }
    }
    error("Data provider returned no value for @mock hint '$hintText' after 3 attempts:\n$lastContent")
  }

  if (literalValue != null) {
    return literalValue.toJsonElementVerbatim()
  }

  error("@mock directive requires either 'hint' or 'value'")
}

/**
 * Builds the JSON schema for the value at [type]/[selections]: a leaf schema for a scalar/enum
 * field, or a (possibly list-wrapped) object schema built from [selections] when the field has a
 * sub-selection.
 */
private fun buildValueSchema(
    schema: Schema,
    type: GQLType,
    selections: List<GQLSelection>,
    fragments: Map<String, GQLFragmentDefinition>,
): JsonObject {
  return when (type) {
    is GQLNonNullType -> buildValueSchema(schema, type.type, selections, fragments)
    is GQLListType -> buildJsonObject {
      put("type", "array")
      put("items", buildValueSchema(schema, type.type, selections, fragments))
    }

    else -> {
      if (selections.isEmpty()) {
        type.toJsonSchemaElement(schema)
      } else {
        buildSelectionSetSchema(schema, type.rawType().name, selections, fragments)
      }
    }
  }
}

private fun buildSelectionSetSchema(
    schema: Schema,
    typeName: String,
    selections: List<GQLSelection>,
    fragments: Map<String, GQLFragmentDefinition>,
): JsonObject {
  val typeDefinition = schema.typeDefinition(typeName)
  val fields = flattenFieldSelections(selections, fragments)

  return buildJsonObject {
    put("type", "object")
    putJsonObject("properties") {
      fields.forEach { field ->
        val fieldDefinition = field.definitionFromScope(schema, typeDefinition)
            ?: error("Unknown field '${field.name}' on type '${typeDefinition.name}'")
        put(field.responseName(), buildValueSchema(schema, fieldDefinition.type, field.selections, fragments))
      }
    }
    putJsonArray("required") { fields.forEach { add(it.responseName()) } }
    put("additionalProperties", false)
  }
}

/**
 * Flattens fragment spreads and inline fragments into a plain list of fields, the way they are
 * seen at execution time. Fragment type conditions are not checked against the parent type: mock
 * generation only needs the shape of the selection, not full validation.
 */
private fun flattenFieldSelections(selections: List<GQLSelection>, fragments: Map<String, GQLFragmentDefinition>): List<GQLField> {
  return selections.flatMap { selection ->
    when (selection) {
      is GQLField -> listOf(selection)
      is GQLInlineFragment -> flattenFieldSelections(selection.selections, fragments)
      is GQLFragmentSpread -> {
        val fragment = fragments[selection.name] ?: error("Unknown fragment '${selection.name}'")
        flattenFieldSelections(fragment.selections, fragments)
      }
    }
  }.distinctBy { it.responseName() }
}

private fun GQLValue.toJsonElementVerbatim(): JsonElement {
  return when (this) {
    is GQLIntValue -> JsonPrimitive(value.toLong())
    is GQLFloatValue -> JsonPrimitive(value.toDouble())
    is GQLStringValue -> JsonPrimitive(value)
    is GQLBooleanValue -> JsonPrimitive(value)
    is GQLEnumValue -> JsonPrimitive(value)
    is GQLNullValue -> JsonNull
    is GQLListValue -> JsonArray(values.map { it.toJsonElementVerbatim() })
    is GQLObjectValue -> JsonObject(fields.associate { it.name to it.value.toJsonElementVerbatim() })
    is GQLVariableValue -> error("Variables are not supported in @mock directives: \$$name")
  }
}
