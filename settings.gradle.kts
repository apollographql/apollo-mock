pluginManagement {
  listOf(repositories, dependencyResolutionManagement.repositories).forEach  {
    it.mavenCentral()
    it.maven("https://storage.googleapis.com/gradleup/m2")
    it.maven("https://storage.googleapis.com/apollo-snapshots/m2")
  }
}

rootProject.name = "apollo-mock"

include(":generator")
include(":executable-schema")
include(":apollo-network-transport")
include(":server")
include(":cli")
include(":gradle-tasks")
include(":gradle-plugin")
