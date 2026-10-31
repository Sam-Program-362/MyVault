plugins { id("com.android.application"); id("org.jetbrains.kotlin.android"); id("com.google.devtools.ksp") }
android {
    namespace = "com.myvault.driverproof"
    compileSdk = 35
    defaultConfig { applicationId = "com.myvault.driverproof"; minSdk = 24; targetSdk = 34; testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    sourceSets.getByName("main").java.srcDir(layout.buildDirectory.dir("generated/myvault"))
}
val copyRealDataSources by tasks.registering(Copy::class) {
    from("../../../../android/app/src/main/java/com/myvault/app/data") { include("AppDatabase.kt", "Entities.kt", "Daos.kt") }
    into(layout.buildDirectory.dir("generated/myvault/com/myvault/app/data"))
}
tasks.configureEach { if (name.startsWith("ksp") || name.startsWith("compile")) dependsOn(copyRealDataSources) }
dependencies {
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("com.github.requery:sqlite-android:3.49.0")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}

// Generated test assets are copied only into the standalone proof test APK.
val copySqlProofAssets by tasks.registering(Copy::class) {
    from("../..") { include("ucd-15.1.json", "expected-keys.json", "sqlite-proof-view.sql", "sqlite-proof-guard.sql") }
    from("../../../../tests/fixtures") { include("tag-name-policy-vectors.json") }
    into(layout.buildDirectory.dir("generated/sql-proof-assets"))
}
android.sourceSets.getByName("androidTest").assets.srcDir(layout.buildDirectory.dir("generated/sql-proof-assets"))
tasks.configureEach { if (name == "mergeDebugAndroidTestAssets") dependsOn(copySqlProofAssets) }
