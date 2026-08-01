plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val developmentSigningEnvironment = mapOf(
    "storeFile" to providers.environmentVariable("OEC_ANDROID_SIGNING_STORE_FILE").orNull,
    "storePassword" to providers.environmentVariable("OEC_ANDROID_SIGNING_STORE_PASSWORD").orNull,
    "keyAlias" to providers.environmentVariable("OEC_ANDROID_SIGNING_KEY_ALIAS").orNull,
    "keyPassword" to providers.environmentVariable("OEC_ANDROID_SIGNING_KEY_PASSWORD").orNull,
)
val developmentSigningEnabled = developmentSigningEnvironment.values.all { !it.isNullOrBlank() }
if (!developmentSigningEnabled && developmentSigningEnvironment.values.any { !it.isNullOrBlank() }) {
    throw GradleException("Android development signing requires all OEC_ANDROID_SIGNING_* values.")
}

android {
    namespace = "dev.openeos.control"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.openeos.control"
        minSdk = 26
        targetSdk = 35
        versionCode = 8
        versionName = "0.1.7"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    val developmentSigningConfig = if (developmentSigningEnabled) {
        signingConfigs.create("development") {
            storeFile = file(developmentSigningEnvironment.getValue("storeFile")!!)
            storePassword = developmentSigningEnvironment.getValue("storePassword")
            keyAlias = developmentSigningEnvironment.getValue("keyAlias")
            keyPassword = developmentSigningEnvironment.getValue("keyPassword")
        }
    } else {
        null
    }

    buildTypes {
        debug {
            developmentSigningConfig?.let { signingConfig = it }
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("com.composables:icons-lucide-android:2.2.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.media3:media3-extractor:1.8.1")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    debugImplementation("androidx.compose.ui:ui-tooling")

    androidTestImplementation(platform("androidx.compose:compose-bom:2024.12.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestUtil("androidx.test.services:test-services:1.5.0")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("org.json:json:20240303")
}
