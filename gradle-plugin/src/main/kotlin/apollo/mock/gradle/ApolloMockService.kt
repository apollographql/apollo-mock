package apollo.mock.gradle

import apollo.mock.gradle.tasks.registerGenerateMockDataTask
import gratatouille.capitalizeFirstLetter
import org.gradle.api.Project
import org.gradle.api.provider.Property
import java.io.File

class ApolloMockService(private val project: Project, private val serviceName: String) {
  private var srcDir: File? = null

  val provider: Property<String> = project.objects.property(String::class.java).convention("ollama")
  val model: Property<String> = project.objects.property(String::class.java)
  val baseUrl: Property<String> = project.objects.property(String::class.java).convention("http://localhost:11434")
  val apiKey: Property<String> = project.objects.property(String::class.java)
  val listSize: Property<Int> = project.objects.property(Int::class.java).convention(8)

  fun srcDir(path: String) {
    srcDir = project.file(path)
  }

  internal fun registerTasks() {
    val dir = checkNotNull(srcDir) {
      "apolloMock: service '$serviceName' must call srcDir(...)"
    }

    val graphqlFiles = project.fileTree(dir) { it.include("*.graphql", "*.graphqls") }
    val outputDir = project.layout.dir(project.provider { File(dir, "mock-data") })

    project.registerGenerateMockDataTask(
        taskName = "generate${serviceName.capitalizeFirstLetter()}MockData",
        taskGroup = "apollo",
        taskDescription = "Generates mock data for the '$serviceName' service",
        graphqlFiles = graphqlFiles,
        graphqlDir = project.provider { dir },
        provider = provider,
        listSize = listSize,
        model = model,
        baseUrl = baseUrl,
        apiKey = apiKey,
        outputDir = outputDir,
    )
  }
}
