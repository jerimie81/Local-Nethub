plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ktlint)
}

android {
    namespace = "com.localnet.hub"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.localnet.hub"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures {
        viewBinding = true
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
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.livedata.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.swiperefreshlayout)

    // Unit tests — run on JVM, no Android emulator needed
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

// Pin ktlint engine to 1.4.1 — supports Kotlin 2.0 parsing.
// ktlint-gradle 12.2.0 defaults to 1.4.x; we pin for reproducibility.
configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
    version.set("1.4.1")
    android.set(true)
    outputToConsole.set(true)
    outputColorName.set("RED")
}
