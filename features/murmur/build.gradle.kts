plugins {
  id("voice.library")
  id("voice.compose")
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.metro)
}

android {
  androidResources {
    enable = true
  }
}

dependencies {
  implementation(projects.core.common)
  implementation(projects.core.data.api)
  implementation(projects.core.documentfile)
  implementation(projects.core.initializer)
  implementation(projects.core.logging.api)
  implementation(projects.core.scanner)
  implementation(projects.core.ui)
  implementation(projects.navigation)

  implementation(libs.datastore)
  implementation(libs.documentFile)
  implementation(libs.okhttp)
  implementation(libs.serialization.json)
  implementation(libs.work.runtime)

  testImplementation(libs.bundles.testing.jvm)
}
