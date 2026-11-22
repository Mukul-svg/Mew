plugins {
  alias(libs.plugins.android.test)
}

android {
  namespace = "com.past9.phoneaos.macrobenchmark"
  compileSdk = 37

  defaultConfig {
    minSdk = 34
    targetSdk = 34
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  targetProjectPath = ":app"
  experimentalProperties["android.experimental.self-instrumenting"] = true

  buildTypes {
    create("benchmark") {
      isDebuggable = true
      signingConfig = signingConfigs.getByName("debug")
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
