plugins { id("com.android.application"); id("org.jetbrains.kotlin.android"); id("org.jetbrains.kotlin.plugin.compose") }
android {
    namespace = "com.pixelwatchgallery.mobile"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.pixelwatchgallery"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "0.2"
    }
    // Both modules use Gradle's same ~/.android/debug.keystore.
    buildTypes {
        debug { signingConfig = signingConfigs.getByName("debug") }
        create("optimized") {
            initWith(getByName("release"))
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            // Personal updates must use the same certificate as the installed debug APKs.
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    buildFeatures { compose = true }
}
dependencies {
    implementation(project(":shared"))
    implementation("androidx.compose.material3:material3")
    testImplementation("junit:junit:4.13.2")
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
