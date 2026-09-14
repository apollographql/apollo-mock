package apollo.mock.networktransport

import apollo.mock.executableschema.mockExecutableSchemaBuilder
import com.apollographql.apollo.api.ApolloRequest
import com.apollographql.apollo.api.ApolloResponse
import com.apollographql.apollo.api.CustomScalarAdapters
import com.apollographql.apollo.api.Operation
import com.apollographql.apollo.api.Subscription
import com.apollographql.apollo.api.json.BufferedSourceJsonReader
import com.apollographql.apollo.api.json.MapJsonWriter
import com.apollographql.apollo.api.parseResponse
import com.apollographql.apollo.ast.GQLDocument
import com.apollographql.apollo.exception.DefaultApolloException
import com.apollographql.apollo.execution.ExecutableSchema
import com.apollographql.apollo.execution.GraphQLRequest
import com.apollographql.apollo.execution.GraphQLResponse
import com.apollographql.apollo.execution.SubscriptionError
import com.apollographql.apollo.execution.SubscriptionEvent
import com.apollographql.apollo.execution.SubscriptionResponse
import com.apollographql.apollo.network.NetworkTransport
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.io.files.Path
import okio.Buffer

/**
 * Returns a [NetworkTransport] that resolves operations against the fake entities loaded from [dataPath]
 * (as written by the generator), instead of a real network endpoint. Queries and mutations are executed
 * once; subscriptions are forwarded to [ExecutableSchema.subscribe] and stream one [ApolloResponse] per
 * emitted event. See [mockExecutableSchemaBuilder].
 */
fun MockNetworkTransport(schema: GQLDocument, dataPath: Path): NetworkTransport {
  return MockNetworkTransportImpl(mockExecutableSchemaBuilder(schema, dataPath).build())
}

private class MockNetworkTransportImpl(private val executableSchema: ExecutableSchema) : NetworkTransport {
  override fun <D : Operation.Data> execute(request: ApolloRequest<D>): Flow<ApolloResponse<D>> {
    val operation = request.operation
    val graphQLRequest = operation.toGraphQLRequest()

    val responses: Flow<ApolloResponse<D>> = if (operation is Subscription<*>) {
      executableSchema.subscribe(graphQLRequest, request.executionContext).map { event ->
        event.toGraphQLResponse().toApolloResponse(operation, request)
      }
    } else {
      flow { emit(executableSchema.execute(graphQLRequest, request.executionContext)) }
          .map { it.toApolloResponse(operation, request) }
    }

    return responses.catch { cause ->
      emit(
          ApolloResponse.Builder(operation, request.requestUuid)
              .exception(DefaultApolloException("MockNetworkTransport failed to execute operation '${operation.name()}'", cause))
              .build()
      )
    }
  }

  override fun dispose() {}
}

private fun <D : Operation.Data> Operation<D>.toGraphQLRequest(): GraphQLRequest {
  val variablesWriter = MapJsonWriter()
  variablesWriter.beginObject()
  serializeVariables(variablesWriter, CustomScalarAdapters.Empty, false)
  variablesWriter.endObject()

  @Suppress("UNCHECKED_CAST")
  val variables = variablesWriter.root() as Map<String, Any?>

  return GraphQLRequest.Builder()
      .document(document())
      .operationName(name())
      .variables(variables)
      .build()
}

private fun SubscriptionEvent.toGraphQLResponse(): GraphQLResponse = when (this) {
  is SubscriptionResponse -> response
  is SubscriptionError -> GraphQLResponse.Builder().errors(errors).build()
  else -> error("Unknown subscription event: $this")
}

private fun <D : Operation.Data> GraphQLResponse.toApolloResponse(
    operation: Operation<D>,
    request: ApolloRequest<D>,
): ApolloResponse<D> {
  val buffer = Buffer()
  serialize(buffer)
  return operation.parseResponse(BufferedSourceJsonReader(buffer), request.requestUuid)
}
