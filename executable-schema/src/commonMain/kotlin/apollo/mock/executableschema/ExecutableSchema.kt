package apollo.mock.executableschema

import com.apollographql.apollo.ast.GQLDocument
import com.apollographql.apollo.execution.ExecutableSchema
import kotlinx.io.files.Path

/**
 * Builds an [ExecutableSchema.Builder] for [schema] that resolves fields against the fake entities and
 * per-operation `@mock` values loaded from [dataPath] (as written by the generator). Query, Mutation and
 * Subscription root fields are all supported.
 *
 * The resolver looks up per-operation mocks using the operation name found in
 * [apollo.mock.executableschema.CurrentOperationName], which the caller (e.g. the server) is responsible
 * for threading through the [com.apollographql.apollo.api.ExecutionContext] passed to `execute`/`subscribe`.
 */
fun mockExecutableSchemaBuilder(schema: GQLDocument, dataPath: Path): ExecutableSchema.Builder {
  val store = EntityStore.load(dataPath)
  val operationMocks = OperationMockStore.load(dataPath)
  return ExecutableSchema.Builder()
    .schema(schema)
    .queryRoot { emptyMap<String, Any?>() }
    .mutationRoot { emptyMap<String, Any?>() }
    .subscriptionRoot { emptyMap<String, Any?>() }
    .resolver(FakeDataResolver(store, operationMocks))
    .exposeServiceCapabilities(false)
    .typeResolver { obj, resolveTypeInfo ->
      (obj as? Map<*, *>)?.get("__typename") as? String
        ?: error("Cannot resolve concrete type for '$obj' (abstract type '${resolveTypeInfo.type}')")
    }
}
