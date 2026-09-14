package apollo.mock.server

import apollo.mock.executableschema.CurrentOperationName
import apollo.mock.executableschema.mockExecutableSchemaBuilder
import com.apollographql.apollo.api.ExecutionContext
import com.apollographql.apollo.ast.toGQLDocument
import com.apollographql.apollo.execution.ExecutableSchema
import com.apollographql.apollo.execution.GraphQLRequest
import kotlinx.coroutines.runBlocking
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServerTest {
  @Test
  fun canRunASimpleQuery() = runBlocking {
    val schema = sampleSchema()

    val request = GraphQLRequest.Builder()
        .document("{ products { name price category { name } } }")
        .build()

    val response = schema.execute(request)

    println(response.data)
    assertTrue(response.errors.isNullOrEmpty(), "Expected no errors but got ${response.errors}")
  }

  @Test
  fun fieldLevelMocksOverrideFakeData() = runBlocking {
    val schema = sampleSchema()
    val request = GraphQLRequest.Builder()
        .document(sampleOperationsText())
        .operationName("GetProducts")
        .build()

    val response = schema.execute(request, ExecutionContext.Empty + CurrentOperationName("GetProducts"))

    assertTrue(response.errors.isNullOrEmpty(), "Expected no errors but got ${response.errors}")
    val products = response.data.asMap()["products"] as List<*>
    assertTrue(products.isNotEmpty())
    products.forEach { product ->
      product as Map<*, *>
      assertEquals(9.99, product["price"])
      assertEquals(true, product["inStock"])
      assertEquals("Small Kitchen Appliances", (product["category"] as Map<*, *>)["name"])
    }
  }

  @Test
  fun wholeOperationMockIsUsedAsRootObject() = runBlocking {
    val schema = sampleSchema()
    val request = GraphQLRequest.Builder()
        .document(sampleOperationsText())
        .operationName("GetOutOfStockProduct")
        .build()

    val response = schema.execute(request, ExecutionContext.Empty + CurrentOperationName("GetOutOfStockProduct"))

    assertTrue(response.errors.isNullOrEmpty(), "Expected no errors but got ${response.errors}")
    val product = response.data.asMap()["product"] as Map<*, *>
    assertEquals("Basic Cotton Socks", product["name"])
    assertEquals(2.49, product["price"])
    assertEquals(false, product["inStock"])
  }

  @Test
  fun operationMocksAreIgnoredWithoutTheMatchingOperationName() = runBlocking {
    val schema = sampleSchema()
    val request = GraphQLRequest.Builder()
        .document(sampleOperationsText())
        .operationName("GetProducts")
        .build()

    // No CurrentOperationName in the execution context: falls back to plain fake entities.
    val response = schema.execute(request)

    assertTrue(response.errors.isNullOrEmpty(), "Expected no errors but got ${response.errors}")
    val products = response.data.asMap()["products"] as List<*>
    assertTrue(products.isNotEmpty())
    assertTrue(products.any { (it as Map<*, *>)["price"] != 9.99 }, "Expected fake (non-mocked) prices")
  }

  @Test
  fun currentOperationNameUsesExplicitOperationNameFirst() {
    val request = GraphQLRequest.Builder()
        .document(sampleOperationsText())
        .operationName("GetOutOfStockProduct")
        .build()

    assertEquals("GetOutOfStockProduct", request.currentOperationName())
  }

  @Test
  fun currentOperationNameFallsBackToTheSoleOperation() {
    val request = GraphQLRequest.Builder()
        .document("{ products { name } }")
        .build()

    assertEquals(null, request.currentOperationName())
  }

  @Test
  fun currentOperationNameIsNullForAmbiguousMultiOperationDocuments() {
    val request = GraphQLRequest.Builder()
        .document(sampleOperationsText())
        .build()

    assertNull(request.currentOperationName())
  }

  private fun sampleSchema(): ExecutableSchema {
    val schemaText = SystemFileSystem.source(Path("../sample/graphql/schema.graphqls")).buffered().use { it.readString() }
    return mockExecutableSchemaBuilder(schemaText.toGQLDocument(), Path("../sample/data")).build()
  }

  private fun sampleOperationsText(): String {
    return SystemFileSystem.source(Path("../sample/graphql/operations.graphql")).buffered().use { it.readString() }
  }

  @Suppress("UNCHECKED_CAST")
  private fun Any?.asMap(): Map<String, Any?> = this as Map<String, Any?>
}
