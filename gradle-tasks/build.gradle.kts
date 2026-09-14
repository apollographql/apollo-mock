import com.gradleup.librarian.gradle.Librarian

plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.ksp)
  alias(libs.plugins.gratatouille.tasks)
}

dependencies {
  implementation(project(":generator"))
}

Librarian.module(project)
