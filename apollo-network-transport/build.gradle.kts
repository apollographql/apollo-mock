import com.gradleup.librarian.gradle.Librarian
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
  id("org.jetbrains.kotlin.multiplatform")
  alias(libs.plugins.apollo)
}

kotlin {
  jvm()

  macosArm64()
  linuxX64()
  @OptIn(ExperimentalWasmDsl::class)
  wasmJs {
    this.browser()
  }

  sourceSets {
    commonMain.dependencies {
      api(project(":executable-schema"))
      api(libs.apollo.runtime)
      api(libs.apollo.execution.runtime)
      api(libs.apollo.ast)
      implementation(libs.kotlinx.coroutines.core)
      implementation(libs.kotlinx.io.core)
    }
    jvmTest.dependencies {
      implementation(kotlin("test"))
      implementation(libs.kotlinx.coroutines.core)
    }
  }
}

apollo {
  service("test") {
    packageName.set("apollo.mock.networktransport.test")
    srcDir("src/jvmTest/graphql")
    outputDirConnection {
      connectToKotlinSourceSet("jvmTest")
    }
  }
}

Librarian.module(project)
