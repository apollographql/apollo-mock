package apollo.mock.gradle

import gratatouille.GExtension
import org.gradle.api.Action
import org.gradle.api.Project

@GExtension(pluginId = "com.apollographql.mock")
abstract class ApolloMockExtension(private val project: Project) {
  private val serviceNames = mutableSetOf<String>()

  fun service(name: String, action: Action<ApolloMockService>) {
    check(serviceNames.add(name)) {
      "apolloMock: service '$name' is already declared."
    }

    ApolloMockService(project, name).also(action::execute).registerTasks()
  }
}
