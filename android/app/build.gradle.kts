import java.util.Properties
import java.io.FileInputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

// Signing credentials are supplied by CI through environment variables /
// GitHub Actions secrets (see .github/workflows/android-apk.yml), or can
// be provided locally via android/keystore.properties (not committed).
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(FileInputStream(keystorePropertiesFile))
}

fun envOrProp(envName: String, propName: String): String? =
    System.getenv(envName) ?: keystoreProperties.getProperty(propName)

val releaseStorePath = envOrProp("ANDROID_KEYSTORE_PATH", "storeFile")
val releaseStoreFile = releaseStorePath?.takeIf { it.isNotBlank() }?.let { file(it) }
val releaseStorePassword = envOrProp("ANDROID_KEYSTORE_PASSWORD", "storePassword")
val releaseKeyAlias = envOrProp("ANDROID_KEY_ALIAS", "keyAlias")
val releaseKeyPassword = envOrProp("ANDROID_KEY_PASSWORD", "keyPassword")
val releaseSigningReady = releaseStoreFile?.isFile == true &&
    listOf(releaseStorePassword, releaseKeyAlias, releaseKeyPassword).all { !it.isNullOrBlank() }

if (providers.gradleProperty("requireReleaseSigning").orNull == "true" && !releaseSigningReady) {
    throw GradleException("Release signing is incomplete: provide a valid keystore path, store password, key alias, and key password.")
}

android {
    namespace = "com.myvault.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.myvault.app"
        minSdk = 24
        targetSdk = 34
        // Bump the APK version for in-place updates of the previous native build.
        versionCode = 4
        versionName = "1.0.3"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        create("release") {
            if (releaseSigningReady) {
                storeFile = releaseStoreFile
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Never silently distribute a release signed with the debug key.
            signingConfig = if (releaseSigningReady) signingConfigs.getByName("release") else null
        }
        debug {
            applicationIdSuffix = ".debug"
            isMinifyEnabled = false
        }
        // A separate, non-debuggable package for safe backup/import from older debug APKs.
        // CI never signs it: the owner must use a stable key they control, offline.
        create("preview") {
            initWith(getByName("release"))
            applicationIdSuffix = ".preview"
            versionNameSuffix = "-preview"
            signingConfig = null
            isDebuggable = false
            matchingFallbacks += listOf("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.03")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.activity:activity-compose:1.9.2")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.8.0")

    // Local, on-device storage (private & offline-first)
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // JSON for import / export (same schema as the web app export)
    // 1.7.x requires the Kotlin 2.0 serialization compiler plugin; this project uses 1.9.24.
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    // Markdown rendering inside entries
    implementation("com.github.jeziellago:compose-markdown:0.5.4")

    // Image loading for covers / wallpaper
    implementation("io.coil-kt:coil-compose:2.6.0")

    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
