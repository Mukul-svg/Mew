plugins {
  alias(libs.plugins.android.test)
}

// The same optional release key as :app. When it exists, the benchmark test APK is signed with it so it can
// instrument a release-signed benchmark build without uninstalling the user's app and its data.
val releaseSigning: Map<String, String> = file((project.findProperty("signingProps") as String?) ?: "${System.getProperty("user.home")}/phone-aos/keys/signing.properties")
  .takeIf { it.exists() }?.readLines()?.filter { "=" in it && !it.startsWith("#") }?.associate { it.substringBefore("=").trim() to it.substringAfter("=").trim() } ?: emptyMap()

android {
  namespace = "com.past9.phoneaos.macrobenchmark"
  compileSdk = 37

  defaultConfig {
    minSdk = 34
    targetSdk = 34
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  signingConfigs {
    if (releaseSigning.isNotEmpty()) create("release") {
      storeFile = file(releaseSigning.getValue("storeFile"))
      storePassword = releaseSigning["storePassword"]
      keyAlias = releaseSigning["keyAlias"]
      keyPassword = releaseSigning["keyPassword"]
    }
  }

  targetProjectPath = ":app"
  experimentalProperties["android.experimental.self-instrumenting"] = true

  buildTypes {
    create("benchmark") {
      isDebuggable = true
      signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
      matchingFallbacks += listOf("release")
    }
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
}

dependencies {
  implementation(libs.junit)
  implementation(libs.androidx.test.ext.junit)
  implementation(libs.androidx.test.runner)
  implementation(libs.androidx.test.uiautomator)
  implementation(libs.androidx.benchmark.macro.junit4)
}
