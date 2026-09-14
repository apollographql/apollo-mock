package apollo.mock.server

import apollo.mock.executableschema.CurrentOperationName
import apollo.mock.executableschema.mockExecutableSchemaBuilder
import com.apollographql.apollo.api.ExecutionContext
import com.apollographql.apollo.ast.GQLOperationDefinition
import com.apollographql.apollo.ast.toGQLDocument
import com.apollographql.apollo.execution.ExecutableSchema
import com.apollographql.apollo.execution.GraphQLRequest
import com.apollographql.apollo.execution.GraphQLResponse
import com.apollographql.execution.ktor.apolloSandboxModule
import com.apollographql.execution.ktor.parseAsGraphQLRequest
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.query
import io.ktor.server.routing.routing
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import okio.Buffer

/**
 * Serves the GraphQL schema at [schemaPath] on [port], resolving fields against the
 * fake entities loaded from the JSON files in [dataPath] (as written by the generator).
 * Blocks until the server is stopped.
 *
 * Pass `0` for [port] to have the OS allocate a free port automatically (`-1` is not
 * supported and throws an `IllegalArgumentException`).
 */
fun serve(
    schemaPath: String,
    dataPath: String,
    port: Int = 4000,
) {
  val schemaText = SystemFileSystem.source(Path(schemaPath)).buffered().use { it.readString() }
  val schema = mockExecutableSchemaBuilder(schemaText.toGQLDocument(), Path(dataPath)).build()

  embeddedServer(CIO, port = port) {
    apolloMockModule(schema)
    apolloSandboxModule()
  }.also {
    println("GraphQL endpoint: http://localhost:$port/graphql")
    println("Sandbox:          http://localhost:$port/")
  }.start(wait = true)
}

/**
 * Like `com.apollographql.execution.ktor.apolloModule`, but also threads the name of the operation
 * being executed through the [ExecutionContext] (as [CurrentOperationName]) so that the mock resolver
 * can look up per-operation mocks, regardless of nesting depth.
 */
private fun Application.apolloMockModule(executableSchema: ExecutableSchema, path: String = "/graphql") {
  routing {
    post(path) { call.respondMockGraphQL(executableSchema) }
    get(path) { call.respondMockGraphQL(executableSchema) }
    query(path) { call.respondMockGraphQL(executableSchema) }
  }
}

private suspend fun ApplicationCall.respondMockGraphQL(executableSchema: ExecutableSchema) {
  val result = request.parseAsGraphQLRequest()
  val contentType = ContentType.parse("application/graphql-response+json")
  if (result.isFailure) {
    respondText(
        contentType = contentType,
        status = HttpStatusCode.BadRequest,
        text = result.exceptionOrNull()?.message ?: "",
    )
    return
  }

  val request = result.getOrThrow()
  val executionContext = ExecutionContext.Empty + CurrentOperationName(request.currentOperationName())
  val response = executableSchema.execute(request, executionContext)
  respondBytes(
      contentType = contentType,
      status = HttpStatusCode.OK,
      bytes = response.toByteArray(),
  )
}

/**
 * The name of the operation this request executes: the explicit `operationName` if one was sent, or the
 * document's only operation if it defines a single one. Mirrors how `ExecutableSchema` itself picks the
 * operation to execute, so a lookup miss here never causes an actual mismatch: it's the same case where
 * that call fails with "multiple operations, use 'operationName'".
 */
internal fun GraphQLRequest.currentOperationName(): String? {
  operationName?.let { return it }
  val document = document ?: return null
  return try {
    document.toGQLDocument().definitions.filterIsInstance<GQLOperationDefinition>().singleOrNull()?.name
  } catch (e: Exception) {
    null
  }
}

private fun GraphQLResponse.toByteArray(): ByteArray {
  val buffer = Buffer()
  serialize(buffer)
  return buffer.readByteArray()
}
