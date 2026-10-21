plugins { id("com.android.library"); id("org.jetbrains.kotlin.android"); id("org.jetbrains.kotlin.plugin.compose") }
android {
    namespace = "com.pixelwatchgallery.shared"
    compileSdk = 36
    defaultConfig { minSdk = 26; testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    buildFeatures { compose = true }
}
dependencies {
    api(platform("androidx.compose:compose-bom:2025.08.00"))
    api("androidx.activity:activity-compose:1.10.1")
    api("androidx.compose.foundation:foundation")
    api("androidx.compose.ui:ui")
    api("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    api("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")
    api("com.google.android.gms:play-services-wearable:20.0.1")
    api("io.coil-kt:coil-compose:2.7.0")
    testImplementation("junit:junit:4.13.2")
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
