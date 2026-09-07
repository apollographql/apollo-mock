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

/**
 * A generic [Resolver] backed by an [EntityStore]:
 *
 * - Query root fields return the stored entities of the field type: all of them for
 *   list fields, a lookup by `id` argument when one is passed, the first one otherwise.
 * - Other fields read the value from the parent entity. `{__typename, id}` references
 *   written by the generator are hydrated into full entities.
 */
internal class FakeDataResolver(private val store: EntityStore) : Resolver {
  override suspend fun resolve(resolveInfo: ResolveInfo): Any? {
    val schema = resolveInfo.schema
    val fieldType = resolveInfo.fieldDefinition().type
    val concreteTypes = schema.concreteTypes(fieldType.rawTypeName())

    if (resolveInfo.parentType == schema.rootTypeNameFor("query")) {
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
