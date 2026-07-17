import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi

plugins {
  alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
  jvmToolchain(17)

  jvm {
    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    binaries {
      executable {
        mainClass.set("azir.server.MainKt")
      }
    }
  }

  sourceSets {
    jvmMain.dependencies {
      implementation(libs.apollo.ast)
      implementation(libs.apollo.execution.runtime)
      implementation(libs.apollo.execution.ktor)
      implementation(libs.ktor.server.netty)
      implementation(libs.kotlinx.serialization.json)
      implementation(libs.logback.classic)
    }
  }
}

// Resolve relative --args paths against the repository root
tasks.withType<JavaExec>().configureEach {
  workingDir = rootDir
}
