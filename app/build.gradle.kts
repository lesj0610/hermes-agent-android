import java.util.Properties

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.kotlin.serialization)
}

// Release signing is driven by keystore.properties, which is deliberately not
// in version control (see .gitignore). Without it the release build still
// assembles unsigned, so CI and local checks work with no secrets present.
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

android {
    namespace = "io.github.lesj0610.hermes"
    compileSdk = 37
    compileSdkMinor = 1
    defaultConfig {
        applicationId = "io.github.lesj0610.hermes"
        minSdk = 26
        targetSdk = 37
        versionCode = 15
        versionName = "1.4"
        // Product name, identical in every locale. Lives here rather than in
        // strings.xml so the debug variant can override it.
        resValue("string", "app_name", "Hermes Agent")
    }

    signingConfigs {
        if (keystoreProperties.getProperty("storeFile") != null) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            // A separate package id so the dev build installs *beside* the
            // release one instead of replacing it. Without this every UI tweak
            // meant uninstalling the working app, and the two are signed with
            // different keys so they cannot overwrite each other anyway.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-dev"
            resValue("string", "app_name", "Hermes Agent dev")
        }

        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }

    bundle {
        language {
            // Google Play splits an AAB by language and installs only the
            // resources matching the device locale. The in-app language picker
            // would then find no Korean strings on an English device and fall
            // back to English — the setting would look broken. Keeping every
            // language in the base module costs a few KB and makes the picker
            // actually work.
            enableSplit = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
      compose = true
      aidl = false
      buildConfig = false
      shaders = false
      // Needed for the app_name resValue that lets the debug build carry its
      // own label.
      resValues = true
    }

    packaging {
      resources {
        excludes += "/META-INF/{AL2.0,LGPL2.1}"
      }
    }

    testOptions {
      unitTests {
        // Robolectric needs the merged resources to inflate anything.
        isIncludeAndroidResources = true
        all {
          it.systemProperty("robolectric.graphicsMode", "NATIVE")
          // Roborazzi reads this from the test JVM. A Gradle -P flag never
          // reaches it without the Roborazzi plugin, which this project does
          // not apply — these screenshots are a look-at tool, not an assertion,
          // so they are always written rather than compared.
          it.systemProperty("roborazzi.test.record", "true")
        }
      }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
  val composeBom = platform(libs.androidx.compose.bom)
  implementation(composeBom)
  androidTestImplementation(composeBom)

  // Core Android dependencies
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.activity.compose)

  // Arch Components
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)

  // Compose
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.compose.material3)

  // Settings storage
  implementation(libs.androidx.datastore.preferences)

  // Hermes gateway transport
  implementation(libs.ktor.client.core)
  implementation(libs.ktor.client.okhttp)
  implementation(libs.ktor.client.content.negotiation)
  implementation(libs.ktor.serialization.kotlinx.json)
  // The dashboard serves the projects RPC over a WebSocket; there is no REST
  // equivalent for it.
  implementation(libs.ktor.client.websockets)
  implementation(libs.kotlinx.serialization.json)

  // Tooling. The preview annotations are compile-time only and live in the
  // debug source set, so nothing here reaches a release build.
  implementation(libs.androidx.compose.ui.tooling.preview)
  debugImplementation(libs.androidx.compose.ui.tooling)
  // Instrumented tests
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  debugImplementation(libs.androidx.compose.ui.test.manifest)

  // Local tests: jUnit, coroutines, Android runner
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)

  // Screen rendering without a device or emulator.
  testImplementation(libs.robolectric)
  testImplementation(libs.roborazzi)
  testImplementation(libs.roborazzi.compose)
  testImplementation(libs.roborazzi.rule)
  testImplementation(libs.androidx.compose.ui.test.junit4)

  // Instrumented tests: jUnit rules and runners
  androidTestImplementation(libs.androidx.test.core)
  androidTestImplementation(libs.androidx.test.ext.junit)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.androidx.test.espresso.core)

  // Navigation
  implementation(libs.androidx.navigation3.ui)
  implementation(libs.androidx.navigation3.runtime)
  implementation(libs.androidx.lifecycle.viewmodel.navigation3)
}
