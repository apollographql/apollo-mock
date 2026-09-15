import com.gradleup.librarian.gradle.Librarian

plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.ksp)
  alias(libs.plugins.gratatouille)
}

Librarian.module(project)

dependencies {
  gratatouille(project(":gradle-tasks"))
  compileOnly(gradleApi())
}
