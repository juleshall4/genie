import com.google.protobuf.gradle.*

plugins {
    id("com.android.application")
    id("com.google.protobuf") version "0.10.0"
}

dependencies {
    implementation("io.noties.markwon:core:4.6.2")
    implementation("io.grpc:grpc-okhttp:1.85.1")
    implementation("io.grpc:grpc-protobuf-lite:1.85.1")
    implementation("io.grpc:grpc-stub:1.85.1")
    implementation("com.google.protobuf:protobuf-javalite:4.36.2")
    testImplementation("io.grpc:grpc-inprocess:1.85.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.nimbusds:nimbus-jose-jwt:10.5")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}

android {
    namespace = "dev.genie.android"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    androidResources.noCompress += "ogg"

    defaultConfig {
        applicationId = "dev.genie.android"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = providers.gradleProperty("genieTestRunner")
            .getOrElse("dev.genie.android.chatgpt.ChatGptLiveCheck")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// Provision a developer's own key after installation; never package it in the APK.
val chirpAdb = androidComponents.sdkComponents.adb
tasks.matching { it.name == "installDebug" }.configureEach {
    doLast {
        for (script in listOf("provision_chirp.py", "restore_shortcut.py")) {
            val process = ProcessBuilder("python3", rootProject.file("scripts/$script").absolutePath, chirpAdb.get().asFile.absolutePath)
                .directory(rootProject.projectDir).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            check(process.waitFor() == 0) { "Device setup failed: $output" }
            println(output.trim())
        }
    }
}

protobuf {
    protoc { artifact = "com.google.protobuf:protoc:4.36.2" }
    generateProtoTasks {
        all().configureEach {
            builtins { maybeCreate("java").option("lite") }
        }
    }
}
