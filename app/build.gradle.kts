plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

/**
 * A stable signing key.
 *
 * The debug APKs this project used to publish were signed with a keystore that Gradle
 * generated fresh on every CI runner, so each download carried a different signature and
 * Android refused to install it over the previous version — the app had to be uninstalled
 * first, losing its local history every time.
 *
 * This key lives in `signing/` on purpose: the same certificate is used for every build,
 * so an update installs over the installed app and keeps its data. It is a personal,
 * self-signed key and is not suitable for Play Store distribution. Anyone who can read
 * this repository can sign a build that Android accepts as an update to this app, which
 * is the trade-off for a phone-only, no-secrets workflow.
 */
val stableKeystore = rootProject.file("signing/footballai-release.p12")
val stableStorePassword = System.getenv("FOOTBALLAI_STORE_PASSWORD") ?: "footballai-signing-2026"
val stableKeyAlias = System.getenv("FOOTBALLAI_KEY_ALIAS") ?: "footballai"

/** Each CI run gets a higher version code, so Android treats a download as an update. */
val ciRunNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull()

android {
    namespace = "com.footballai.app"
    compileSdk = 34

    signingConfigs {
        if (stableKeystore.exists()) {
            create("stable") {
                storeFile = stableKeystore
                // PKCS#12 keeps the key password equal to the store password.
                storeType = "PKCS12"
                storePassword = stableStorePassword
                keyAlias = stableKeyAlias
                keyPassword = stableStorePassword
            }
        }
    }

    defaultConfig {
        applicationId = "com.footballai.app"
        minSdk = 26
        targetSdk = 34
        // A new build must never look older than the installed one.
        versionCode = 20 + (ciRunNumber ?: 0)
        versionName = ciRunNumber?.let { "0.7.$it" } ?: "0.7.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        resourceConfigurations += listOf("en", "fa")
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            // Signed with the stable key when it is present, so a debug build also
            // installs as an update instead of demanding an uninstall.
            signingConfig = signingConfigs.findByName("stable")
        }
        release {
            signingConfig = signingConfigs.findByName("stable")
            // Shrinking stays off until its output has been checked on a device. A
            // smaller APK is worth little next to an app that cannot be run at all, and
            // this environment has no device to test a minified build on.
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = false
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf("-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi")
    }

    buildFeatures {
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = libs.versions.composeCompiler.get()
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/DEPENDENCIES"
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(project(":engine"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)

    implementation(libs.okhttp)
    implementation(libs.coil.compose)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
    testImplementation(libs.androidx.room.testing)

    androidTestImplementation("androidx.test:core:1.5.0")
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.espresso)
}
