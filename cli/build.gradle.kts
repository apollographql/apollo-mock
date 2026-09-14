import com.gradleup.librarian.gradle.Librarian
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi

plugins {
  alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
  jvm {
    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    binaries {
      executable {
        mainClass.set("apollo.mock.cli.MainKt")
        applicationName = "apollo-mock"
      }
    }
  }

  macosArm64 {
    binaries {
      executable {
        entryPoint = "apollo.mock.cli.main"
        baseName = "apollo-mock"
      }
    }
  }
  linuxX64 {
    binaries {
      executable {
        entryPoint = "apollo.mock.cli.main"
        baseName = "apollo-mock"
      }
    }
  }
//  linuxArm64()

  sourceSets {
    commonMain.dependencies {
      implementation(project(":generator"))
      implementation(project(":server"))
      implementation(libs.clikt)
    }
  }
}

// Resolve relative --args paths against the repository root
tasks.withType<JavaExec>().configureEach {
  workingDir = rootDir
}

Librarian.module(project)
