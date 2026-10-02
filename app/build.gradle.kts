plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 签名配置 — 直接从 keystore.properties 读取
val signingProps = mutableMapOf<String, String>()
file("keystore.properties").takeIf { it.exists() }?.let { f ->
    f.readLines().forEach { line ->
        val p = line.split("=", limit = 2)
        if (p.size == 2) signingProps[p[0]] = p[1]
    }
}

android {
    namespace = "com.tiramisu.deepseekwidget"
    compileSdk = 34

    signingConfigs {
        getByName("debug") {
            storeFile = file("../debug.keystore")
            storePassword = "android"
            keyAlias = "debug"
            keyPassword = "android"
        }
        create("release") {
            storeFile = signingProps["storeFile"]?.let { file(it) }
            storePassword = signingProps["storePassword"] ?: "android"
            keyAlias = signingProps["keyAlias"] ?: "release"
            keyPassword = signingProps["keyPassword"] ?: "android"
        }
    }




    defaultConfig {
        applicationId = "com.tiramisu.deepseekwidget"
        minSdk = 26
        targetSdk = 34
        versionCode = 4
        versionName = "1.2.1"
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // Core
    implementation("androidx.core:core-ktx:1.12.0")



    // AppWidget
    implementation("androidx.glance:glance-appwidget:1.0.0")

    // WorkManager for periodic updates
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    // HTTP client
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // JSON parsing
    implementation("com.google.code.gson:gson:2.10.1")

    // Encrypted storage for API key
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Fix: security-crypto 的 Tink 依赖需要 error_prone_annotations
    implementation("com.google.errorprone:error_prone_annotations:2.26.1")


}
