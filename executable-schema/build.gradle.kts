import com.gradleup.librarian.gradle.Librarian
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
  id("org.jetbrains.kotlin.multiplatform")
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
      implementation(libs.apollo.execution.runtime)
      implementation(libs.kotlinx.serialization.json)
      implementation(libs.kotlinx.io.core)
    }
  }
}

Librarian.module(project)
