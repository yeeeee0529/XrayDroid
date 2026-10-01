plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jlleitschuh.gradle.ktlint")
}

android {
    namespace = "io.github.xraydroid"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    defaultConfig {
        applicationId = providers.gradleProperty("validationApplicationId").getOrElse("io.github.xraydroid")
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters += "arm64-v8a" }
        buildConfigField("String", "XUI_VERSION", "\"3.8.5\"")
        buildConfigField("String", "XRAY_VERSION", "\"26.6.27\"")
        buildConfigField("String", "FRP_VERSION", "\"0.71.0\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
        jniLibs {
            useLegacyPackaging = true
            keepDebugSymbols += "**/libxui.so"
            keepDebugSymbols += "**/libxray.so"
            keepDebugSymbols += "**/libfrpc.so"
        }
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
    lint {
        warningsAsErrors = true
        // 固定已驗證的工具與套件版本，第一版限定 arm64 Android 裝置。
        disable += listOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion", "ChromeOsAbiSupport")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation(platform("androidx.compose:compose-bom:2025.10.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3:1.5.0-alpha04")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
}

val verifyCore by tasks.registering {
    doLast {
        listOf("libxui.so", "libxray.so", "libfrpc.so").forEach { name ->
            check(file("src/main/jniLibs/arm64-v8a/$name").isFile) {
                "Missing $name. Run scripts/build-core.sh before packaging the APK."
            }
        }
        listOf("geoip.dat", "geosite.dat").forEach { name ->
            check(file("src/main/assets/core/$name").isFile) {
                "Missing $name. Run scripts/build-core.sh before packaging the APK."
            }
        }
    }
}
tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(verifyCore) }
