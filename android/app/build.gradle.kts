import com.android.build.api.instrumentation.FramesComputationMode
import com.android.build.api.instrumentation.InstrumentationScope
import dev.openeos.probe.MeasureWriterVisitorFactory

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// LOCAL DIAGNOSTIC EXPERIMENT. Never merge or publish this probe with a product/release.
val measureWriterProbe = providers.gradleProperty("eosMeasureWriterProbe").orNull == "true"
val measureWriterInvocation = providers.gradleProperty("eosMeasureWriterInvocation").orNull

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

// Development Preview publication uses a signed DEBUG APK. Release-variant exclusion alone
// cannot protect that path: a diagnostic APK must never use the publication signing config.
if (measureWriterProbe && developmentSigningEnabled) {
    throw GradleException("The local measure-writer probe cannot use development publication signing.")
}

if (measureWriterProbe && !Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}").matches(measureWriterInvocation ?: "")) {
    throw GradleException("The local measure-writer probe requires a fresh eosMeasureWriterInvocation nonce.")
}

android {
    namespace = "dev.openeos.control"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.openeos.control"
        minSdk = 26
        targetSdk = 35
        versionCode = 29
        versionName = "0.13.0"
        testInstrumentationRunner = if (measureWriterProbe) {
            "dev.openeos.control.diagnostics.MeasureWriterProbeRunner"
        } else "androidx.test.runner.AndroidJUnitRunner"
        if (measureWriterProbe) {
            testInstrumentationRunnerArguments["eosMeasureWriterInvocation"] = measureWriterInvocation!!
        }
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
            if (providers.gradleProperty("localDebugApplicationIdSuffix").orNull == "true") {
                applicationIdSuffix = ".debug"
            }
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

    if (measureWriterProbe) {
        sourceSets.getByName("debug").java.srcDir("../measureWriterProbe/java")
        sourceSets.getByName("androidTest").java.srcDir("../measureWriterProbeAndroidTest/java")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        // A skipped or crashed custom registry must fail the quality gate.
        fatal += setOf("ObsoleteLintCustomCheck", "LintError")
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
    implementation(project(":camera-import-contract"))
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("com.composables:icons-lucide-android:2.2.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.media3:media3-extractor:1.8.1")
    implementation("androidx.media3:media3-exoplayer:1.8.1")
    implementation("androidx.media3:media3-ui:1.8.1")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    debugImplementation("androidx.compose.ui:ui-tooling")

    androidTestImplementation(platform("androidx.compose:compose-bom:2024.12.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
    androidTestImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    androidTestUtil("androidx.test.services:test-services:1.5.0")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("org.json:json:20240303")
}

// Gradle's built-in FULL formatter omits suppressed exceptions, including the original
// failure carried by kotlinx-coroutines-test's UncaughtExceptionsBeforeTest. Preserve the
// Throwable's complete hierarchy for failed tests only; no test or gate is changed.
tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    addTestListener(object : org.gradle.api.tasks.testing.TestListener {
        override fun beforeSuite(suite: org.gradle.api.tasks.testing.TestDescriptor) = Unit
        override fun afterSuite(suite: org.gradle.api.tasks.testing.TestDescriptor, result: org.gradle.api.tasks.testing.TestResult) = Unit
        override fun beforeTest(test: org.gradle.api.tasks.testing.TestDescriptor) = Unit
        override fun afterTest(test: org.gradle.api.tasks.testing.TestDescriptor, result: org.gradle.api.tasks.testing.TestResult) {
            if (result.resultType == org.gradle.api.tasks.testing.TestResult.ResultType.FAILURE) {
                result.exceptions.forEach { logger.error(it.stackTraceToString()) }
            }
        }
    })
}

// ALL is essential: MeasureAndLayoutDelegate belongs to an external Compose dependency.
// Release variants never register a transform or include the diagnostic runtime sources.
if (measureWriterProbe) {
    androidComponents.onVariants(androidComponents.selector().withBuildType("debug")) { variant ->
        variant.instrumentation.transformClassesWith(
            MeasureWriterVisitorFactory::class.java, InstrumentationScope.ALL,
        ) {}
        variant.instrumentation.setAsmFramesComputationMode(
            FramesComputationMode.COMPUTE_FRAMES_FOR_INSTRUMENTED_METHODS,
        )
    }
}
