package apollo.mock.gradle.tasks

import apollo.mock.generator.generate
import apollo.mock.generator.loadGraphqlFiles
import gratatouille.tasks.GInputFiles
import gratatouille.tasks.GInternal
import gratatouille.tasks.GManuallyWired
import gratatouille.tasks.GOutputDirectory
import gratatouille.tasks.GTask
import kotlinx.coroutines.runBlocking
import java.io.File

@GTask
internal fun generateMockData(
  graphqlFiles: GInputFiles,
  @GInternal graphqlDir: File,
  provider: String,
  listSize: Int,
  model: String?,
  baseUrl: String,
  apiKey: String?,
  @GManuallyWired outputDir: GOutputDirectory,
) {
  check(graphqlFiles.isNotEmpty()) {
    "apolloMock: no .graphql/.graphqls files found in '$graphqlDir'"
  }

  val files = loadGraphqlFiles(graphqlDir.path)
  runBlocking {
    generate(
      schema = files.schema,
      operations = files.operations.asSequence(),
      outputPath = outputDir.path,
      provider = provider,
      count = listSize,
      model = model,
      baseUrl = baseUrl,
      apiKey = apiKey,
    )
  }
}
