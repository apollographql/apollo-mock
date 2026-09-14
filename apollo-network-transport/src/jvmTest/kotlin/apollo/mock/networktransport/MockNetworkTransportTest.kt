package apollo.mock.networktransport

import apollo.mock.networktransport.test.ProductAddedSubscription
import apollo.mock.networktransport.test.ProductsQuery
import com.apollographql.apollo.api.ApolloRequest
import com.apollographql.apollo.ast.toGQLDocument
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MockNetworkTransportTest {
  private val schema = """
    type Query { products: [Product!]! }
    type Subscription { productAdded: Product }
    type Product { id: ID! name: String! }
  """.trimIndent().toGQLDocument()

  private val transport = MockNetworkTransport(schema, Path("src/jvmTest/data"))

  @Test
  fun query() = runBlocking {
    val response = transport.execute(ApolloRequest.Builder(ProductsQuery()).build()).first()

    assertTrue(response.errors.isNullOrEmpty(), "Expected no errors but got ${response.errors}")
    assertEquals(listOf("Widget"), response.data?.products?.map { it.name })
  }

  @Test
  fun subscription() = runBlocking {
    val response = transport.execute(ApolloRequest.Builder(ProductAddedSubscription()).build()).first()

    // Subscription root fields resolve against the (always empty) subscriptionRoot, since the generator
    // only produces static fake data - this exercises the transport's plumbing, not real pushed data.
    assertTrue(response.errors.isNullOrEmpty(), "Expected no errors but got ${response.errors}")
    assertNull(response.data?.productAdded)
  }
}
