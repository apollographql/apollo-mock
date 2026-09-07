import com.gradleup.librarian.gradle.Librarian

plugins {
  id("org.jetbrains.kotlin.multiplatform")
}

kotlin {
  jvm()

  macosArm64()
  linuxX64()

//  linuxArm64()

  sourceSets {
    commonMain.dependencies {
      implementation(project(":executable-schema"))
      implementation(libs.apollo.ast)
      implementation(libs.apollo.execution.runtime)
      implementation(libs.apollo.execution.ktor)
      implementation(libs.kotlinx.io.core)
      implementation(libs.logback.classic)
      implementation(libs.ktor.server.cio)
    }
    jvmTest.dependencies {
      implementation(kotlin("test"))
      implementation(libs.kotlinx.coroutines.core)
    }
  }
}

Librarian.module(project)
