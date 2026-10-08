plugins { java }
repositories { google(); mavenCentral() }
dependencies {
    // buildSrc's exported runtime must resolve the factory's AGP API supertype. compileOnly
    // succeeds at javac but leaves that supertype invisible when app configuration loads it.
    // API-only artifact at the existing AGP version: no Android plugin implementation/descriptor
    // and no application runtime dependency. Keep normal API transitives (including ASM 9.6).
    implementation("com.android.tools.build:gradle-api:8.6.1")
}
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
