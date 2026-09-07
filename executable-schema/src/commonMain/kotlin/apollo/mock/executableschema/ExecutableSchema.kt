package apollo.mock.executableschema

import com.apollographql.apollo.ast.GQLDocument
import com.apollographql.apollo.execution.ExecutableSchema

/**
 * Builds an [ExecutableSchema] for [schema] that resolves fields against the fake entities in [store].
 */
fun executableSchema(schema: GQLDocument, store: EntityStore): ExecutableSchema {
  return ExecutableSchema.Builder()
      .schema(schema)
      .queryRoot { emptyMap<String, Any?>() }
      .resolver(FakeDataResolver(store))
      .typeResolver { obj, resolveTypeInfo ->
        (obj as? Map<*, *>)?.get("__typename") as? String
            ?: error("Cannot resolve concrete type for '$obj' (abstract type '${resolveTypeInfo.type}')")
      }
      .build()
}
