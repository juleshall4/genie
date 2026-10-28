plugins {
    id("com.android.application")
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.nimbusds:nimbus-jose-jwt:10.5")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
}

android {
    namespace = "dev.genie.android"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "dev.genie.android"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "dev.genie.android.chatgpt.ChatGptLiveCheck"
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
