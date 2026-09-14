package apollo.mock.executableschema

import com.apollographql.apollo.api.Optional
import com.apollographql.apollo.ast.GQLInterfaceTypeDefinition
import com.apollographql.apollo.ast.GQLListType
import com.apollographql.apollo.ast.GQLNamedType
import com.apollographql.apollo.ast.GQLNonNullType
import com.apollographql.apollo.ast.GQLObjectTypeDefinition
import com.apollographql.apollo.ast.GQLType
import com.apollographql.apollo.ast.GQLUnionTypeDefinition
import com.apollographql.apollo.ast.Schema
import com.apollographql.apollo.execution.ResolveInfo
import com.apollographql.apollo.execution.Resolver
import kotlin.collections.get
import kotlinx.coroutines.flow.flowOf

/**
 * A generic [Resolver] backed by an [EntityStore] and [OperationMockStore]:
 *
 * - A field whose GraphQL path (see [mockPath]) has a per-operation mock (looked up using the operation
 *   name found in [CurrentOperationName]) returns that mocked value, taking precedence over everything else.
 * - Otherwise, Query root fields return the stored entities of the field type: all of them for
 *   list fields, a lookup by `id` argument when one is passed, the first one otherwise. A whole-operation
 *   mock (the empty path) is used as a stand-in root object for Query/Mutation/Subscription root fields
 *   when present.
 * - Subscription root fields return a one-shot [kotlinx.coroutines.flow.Flow] emitting the same
 *   value a Mutation/Query field would, since the generator only produces static fake data.
 * - Other fields read the value from the parent entity. `{__typename, id}` references
 *   written by the generator are hydrated into full entities.
 */
internal class FakeDataResolver(
    private val store: EntityStore,
    private val operationMocks: OperationMockStore,
) : Resolver {
  override suspend fun resolve(resolveInfo: ResolveInfo): Any? {
    val schema = resolveInfo.schema
    val fieldType = resolveInfo.fieldDefinition().type
    val concreteTypes = schema.concreteTypes(fieldType.rawTypeName())
    val mocks = operationMocks.forOperation(resolveInfo.executionContext[CurrentOperationName]?.name)

    val value = resolveValue(resolveInfo, schema, fieldType, concreteTypes, mocks)
    return if (resolveInfo.parentType == schema.rootTypeNameOrNullFor("subscription")) {
      flowOf(value)
    } else {
      value
    }
  }

  private fun resolveValue(
      resolveInfo: ResolveInfo,
      schema: Schema,
      fieldType: GQLType,
      concreteTypes: List<String>,
      mocks: Map<String, Any?>?,
  ): Any? {
    val path = resolveInfo.mockPath()
    if (mocks != null && path in mocks) {
      return hydrate(mocks[path], concreteTypes)
    }

    val isRootField = resolveInfo.parentType == schema.rootTypeNameOrNullFor("query") ||
        resolveInfo.parentType == schema.rootTypeNameOrNullFor("mutation") ||
        resolveInfo.parentType == schema.rootTypeNameOrNullFor("subscription")

    if (isRootField) {
      val rootMock = mocks?.get("") as? Map<*, *>
      if (rootMock != null) {
        return hydrate(rootMock[resolveInfo.responseName()], concreteTypes)
      }
    }

    if (resolveInfo.parentType == schema.rootTypeNameOrNullFor("query")) {
      if (!store.contains(concreteTypes)) {
        return null
      }
      val id = resolveInfo.getArgument<Any?>("id")
      return when {
        fieldType.isList() -> store.all(concreteTypes)
        id is Optional.Present -> store.byId(concreteTypes, id.value)
        else -> store.all(concreteTypes).firstOrNull()
      }
    }

    val parent = resolveInfo.parentObject as? Map<*, *> ?: return null
    return hydrate(parent[resolveInfo.fieldName], concreteTypes)
  }

  /**
   * Replaces `{__typename, id}` references with the full entity from the store.
   */
  private fun hydrate(value: Any?, concreteTypes: List<String>): Any? {
    return when (value) {
      is List<*> -> value.map { hydrate(it, concreteTypes) }
      is Map<*, *> -> {
        val typeName = value["__typename"] as? String ?: concreteTypes.singleOrNull()
        val id = value["id"]
        if (typeName != null && id != null) {
          store.byId(listOf(typeName), id) ?: value
        } else {
          value
        }
      }

      else -> value
    }
  }
}

private fun GQLType.rawTypeName(): String = when (this) {
  is GQLNonNullType -> type.rawTypeName()
  is GQLListType -> type.rawTypeName()
  is GQLNamedType -> name
}

private fun GQLType.isList(): Boolean = when (this) {
  is GQLNonNullType -> type.isList()
  is GQLListType -> true
  is GQLNamedType -> false
}

private fun Schema.concreteTypes(typeName: String): List<String> {
  return when (typeDefinitions[typeName]) {
    is GQLObjectTypeDefinition -> listOf(typeName)
    is GQLInterfaceTypeDefinition, is GQLUnionTypeDefinition -> possibleTypes(typeName).toList()
    else -> emptyList()
  }
}

/**
 * The response name of the field currently being resolved, matching how the generator's `@mock` directive
 * processing builds its per-operation mock paths (aliases included). [ResolveInfo.path] always ends with
 * this field's response name: list indices only ever appear as intermediate elements.
 */
private fun ResolveInfo.responseName(): String = path.last() as String

/**
 * The dot-separated GraphQL path of the field currently being resolved, matching the keys produced by the
 * generator's `@mock` directive processing: list indices are stripped since a mock applies to every item
 * of a list field, not to a specific index.
 */
private fun ResolveInfo.mockPath(): String = path.filterIsInstance<String>().joinToString(".")
