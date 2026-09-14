package apollo.mock.generator

import kotlinx.coroutines.runBlocking
import kotlinx.io.IOException
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.io.path.createTempDirectory
import kotlin.test.Test

class GeneratorTest {
  @Test
  fun generateWithRandomProvider() = runBlocking {
    val files = loadGraphqlFiles("../sample/graphql")
    val outputDir = "build/apollo-mock-test"

    try {
      SystemFileSystem.delete(Path(outputDir), mustExist = false)
    } catch (_: IOException) {
    }
    SystemFileSystem.createDirectories(Path(outputDir))

    generate(
        schema = files.schema,
        operations = files.operations.asSequence(),
        outputPath = outputDir,
        provider = "ollama",
    )
  }
}
