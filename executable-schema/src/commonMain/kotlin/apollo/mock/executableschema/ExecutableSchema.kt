package apollo.mock.executableschema

import com.apollographql.apollo.ast.GQLDocument
import com.apollographql.apollo.execution.ExecutableSchema
import kotlinx.io.files.Path

/**
 * Builds an [ExecutableSchema.Builder] for [schema] that resolves fields against the fake entities loaded
 * from [dataPath] (as written by the generator). Query, Mutation and Subscription root fields are all
 * supported.
 */
fun mockExecutableSchemaBuilder(schema: GQLDocument, dataPath: Path): ExecutableSchema.Builder {
  val store = EntityStore.load(dataPath)
  return ExecutableSchema.Builder()
    .schema(schema)
    .queryRoot { emptyMap<String, Any?>() }
    .mutationRoot { emptyMap<String, Any?>() }
    .subscriptionRoot { emptyMap<String, Any?>() }
    .resolver(FakeDataResolver(store))
    .exposeServiceCapabilities(false)
    .typeResolver { obj, resolveTypeInfo ->
      (obj as? Map<*, *>)?.get("__typename") as? String
        ?: error("Cannot resolve concrete type for '$obj' (abstract type '${resolveTypeInfo.type}')")
    }
}
