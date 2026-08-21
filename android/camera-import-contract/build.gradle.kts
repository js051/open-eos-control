plugins {
    id("org.jetbrains.kotlin.jvm")
}

group = "dev.openeos.control"
version = rootProject.file("../contracts/camera-import/v1/VERSION").readText().trim()

base {
    archivesName.set("open-eos-camera-import-contract-kotlin")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

sourceSets {
    test {
        resources.srcDir(rootProject.file("../contracts/camera-import/v1"))
    }
}

dependencies {
    compileOnly("org.json:json:20240303")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}

tasks.test {
    useJUnit()
}

tasks.jar {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    manifest {
        attributes(
            "Implementation-Title" to "Open EOS Camera Import Contract",
            "Implementation-Version" to project.version,
            "Camera-Import-Wire-Version" to "1.0",
        )
    }
    from(rootProject.file("../LICENSE")) {
        into("META-INF")
        rename { "LICENSE.txt" }
    }
}
