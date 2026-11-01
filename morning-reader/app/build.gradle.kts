import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Keys come from the ignored .env at the repo root (see README) and are baked into the APK.
val env = Properties().apply {
    rootProject.file(".env").takeIf { it.exists() }?.reader()?.use { load(it) }
}

android {
    namespace = "com.alejoacelas.morningreader"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.alejoacelas.morningreader"
        minSdk = 31
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
        buildConfigField("String", "ANTHROPIC_API_KEY", "\"${env.getProperty("ANTHROPIC_API_KEY", "")}\"")
        buildConfigField("String", "GITHUB_ISSUES_TOKEN", "\"${env.getProperty("GITHUB_ISSUES_TOKEN", "")}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.work:work-runtime-ktx:2.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("com.squareup.okhttp3:okhttp:5.5.0")
    implementation("org.jsoup:jsoup:1.23.2")
    implementation("net.dankito.readability4j:readability4j:1.0.8")
}
