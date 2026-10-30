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
