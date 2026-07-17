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
        mainClass.set("azir.generator.MainKt")
      }
    }
  }

  sourceSets {
    jvmMain.dependencies {
      implementation(libs.apollo.ast)
      implementation(libs.langchain4j.anthropic)
      implementation(libs.kotlinx.serialization.json)
    }
  }
}

// Resolve relative --args paths against the repository root
tasks.withType<JavaExec>().configureEach {
  workingDir = rootDir
}
