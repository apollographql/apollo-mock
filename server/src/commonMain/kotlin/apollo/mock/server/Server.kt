package apollo.mock.server

import apollo.mock.executableschema.mockExecutableSchemaBuilder
import com.apollographql.apollo.ast.toGQLDocument
import com.apollographql.execution.ktor.apolloModule
import com.apollographql.execution.ktor.apolloSandboxModule
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString

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
    apolloModule(schema)
    apolloSandboxModule()
  }.also {
    println("GraphQL endpoint: http://localhost:$port/graphql")
    println("Sandbox:          http://localhost:$port/")
  }.start(wait = true)
}
