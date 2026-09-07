package apollo.mock.generator

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

class OllamaClientTest {
  @Test
  fun defaultOllamaModelReturnsALocallyPulledModel() = runBlocking {
    val model = defaultOllamaModel("http://localhost:11434")
    assertTrue(model.isNotBlank(), "Expected a non-blank model name, got '$model'")
  }
}
