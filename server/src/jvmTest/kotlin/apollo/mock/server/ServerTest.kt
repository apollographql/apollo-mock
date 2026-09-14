package apollo.mock.server

import apollo.mock.executableschema.mockExecutableSchemaBuilder
import com.apollographql.apollo.ast.toGQLDocument
import com.apollographql.apollo.execution.GraphQLRequest
import kotlinx.coroutines.runBlocking
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlin.test.Test
import kotlin.test.assertTrue

class ServerTest {
  @Test
  fun canRunASimpleQuery() = runBlocking {
    val schemaText = SystemFileSystem.source(Path("../sample/graphql/schema.graphqls")).buffered().use { it.readString() }

    val schema = mockExecutableSchemaBuilder(schemaText.toGQLDocument(), Path("../sample/data")).build()

    val request = GraphQLRequest.Builder()
        .document("{ products { name price category { name } } }")
        .build()

    val response = schema.execute(request)

    println(response.data)
    assertTrue(response.errors.isNullOrEmpty(), "Expected no errors but got ${response.errors}")
  }
}
