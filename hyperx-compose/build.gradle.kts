import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.kotlin.plugin.parcelize")
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.fromTarget("11")
    }
}

android {
    namespace = "dev.lackluster.hyperx.compose"
    compileSdk = 37

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    defaultConfig {
        minSdk = 31
        consumerProguardFiles("consumer-rules.pro")
    }
    buildFeatures {
        compose = true
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
}

@Suppress("UseTomlInstead")
dependencies {
    api("top.yukonga.miuix.kmp:miuix-ui:0.9.0")
    api("top.yukonga.miuix.kmp:miuix-icons:0.9.0")
    api("dev.chrisbanes.haze:haze:1.7.2")
    api("androidx.compose.foundation:foundation:1.10.5")
    api("androidx.activity:activity-compose:1.13.0")
    api("top.yukonga.miuix.kmp:miuix-navigation3-ui:0.9.0")
    api("top.yukonga.miuix.kmp:miuix-preference:0.9.0")
    api("androidx.navigation3:navigation3-runtime:1.1.0-rc01")
    api("org.jetbrains.androidx.navigationevent:navigationevent-compose:1.0.1")
    api("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("com.github.promeg:tinypinyin:2.0.3") // maven("https://maven.aliyun.com/repository/public")
    implementation("io.coil-kt.coil3:coil-compose:3.4.0")
}
