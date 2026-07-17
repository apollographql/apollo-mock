pluginManagement {
  listOf(repositories, dependencyResolutionManagement.repositories).forEach  {
    it.mavenCentral()
  }
}

rootProject.name = "apollo-mock"

include(":generator")
include(":server")
