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
  defaultConfig {
    consumerProguardFiles("consumer-rules.pro")
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
  implementation(libs.libtorrent4j)
  runtimeOnly(libs.libtorrent4j.arm)
  runtimeOnly(libs.libtorrent4j.arm64)
  runtimeOnly(libs.libtorrent4j.x64)
  implementation(libs.okhttp)
  implementation(libs.serialization.json)
  implementation(libs.work.runtime)

  testImplementation(libs.bundles.testing.jvm)
}
