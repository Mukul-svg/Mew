pluginManagement {
  repositories {
    google {
      content {
        includeGroupByRegex("com\\.android.*")
        includeGroupByRegex("com\\.google.*")
        includeGroupByRegex("androidx.*")
      }
    }
    mavenCentral()
    gradlePluginPortal()
  }
}

plugins { id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0" }

dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories {
    google()
    mavenCentral()
    // The wake word engine (sherpa-onnx, Apache-2.0) is published as an AAR on its GitHub release, not on Maven Central.
    ivy {
      url = uri("https://github.com/k2-fsa/sherpa-onnx/releases/download")
      patternLayout { artifact("v[revision]/[module]-[revision].[ext]") }
      metadataSources { artifact() }
      content { includeGroup("com.k2fsa.sherpa.onnx") }
    }
  }
}

rootProject.name = "Mew"

include(":app")
include(":wearproto")
include(":macrobenchmark")

// Phone-only by default (0.16.0): the Wear OS app and watch faces stay in the repository, but are
// configured only when asked for with -PwithWear. The phone app still depends on :wearproto.
if (providers.gradleProperty("withWear").isPresent) {
  // The watch app shares the phone's protocol and applicationId. Build it only on demand:
  //   ./gradlew -PwithWear :wear:assembleDebug
  include(":wear")

  // The three Mew watch faces (Watch Face Format, resources only, one face per package).
  include(":facestill", ":faceorb", ":facedata")
  project(":facestill").projectDir = file("watchface/still")
  project(":faceorb").projectDir = file("watchface/orb")
  project(":facedata").projectDir = file("watchface/data")
}
