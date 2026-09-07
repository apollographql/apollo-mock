import com.gradleup.librarian.gradle.Librarian
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
  alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
  jvm()

  macosArm64()
  linuxX64()
  @OptIn(ExperimentalWasmDsl::class)
  wasmJs {
    this.browser()
  }
//  linuxArm64()

  sourceSets {
    commonMain.dependencies {
      implementation(libs.apollo.ast)
      implementation(libs.ktor.client.cio)
      implementation(libs.kotlinx.serialization.json)
      implementation(libs.kotlinx.io.core)
      api(libs.kotlinx.coroutines.core)
    }
    jvmTest.dependencies {
      implementation(kotlin("test"))
    }
  }
}

Librarian.module(project)
