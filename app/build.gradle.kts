plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.secureguard.mdm"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.secureguard.mdm"
        minSdk = 22
        targetSdk = 36
        versionCode = 3
        versionName = "0.5"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
        multiDexEnabled = true
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        getByName("debug") {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
        viewBinding = true
    }

    externalNativeBuild {
        ndkBuild {
            path("src/main/jni/Android.mk")
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    // Kotlin/Android Core
    implementation(libs.androidx.core.ktx)

    // Room Database
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // General Android Views
    implementation(libs.material)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)

    // Jetpack Compose & UI
    implementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.coil.compose)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    // Lifecycle & Navigation
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.hilt.navigation.compose)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // JSON & Network (Ktor)
    implementation(libs.gson)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.client.logging)
    implementation(libs.ktor.client.cio)
    // StoreKit
    implementation(libs.storekit.gplay.scrapper)
    implementation(libs.storekit.gplay.core)
    implementation(libs.storekit.fdroid.api)
    implementation(libs.storekit.fdroid.core)
    implementation(libs.storekit.aptoide.api)
    implementation(libs.storekit.aptoide.core)
    implementation(libs.storekit.apkcombo.scraper)
    implementation(libs.storekit.apkcombo.core)
    implementation(libs.storekit.apkpure.api)
    implementation(libs.storekit.apkpure.core)
    implementation(libs.storekit.apklinkresolver)
    implementation(libs.storekit.authenticity)
    implementation(libs.kotlinx.serialization.json)
    // Security
    implementation(libs.bcrypt)

    // Accompanist
    implementation(libs.accompanist.drawablepainter)

    // Testing
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    
    // date for kiosk
    implementation(libs.zmanim)

    // GPlayAPI
    implementation(libs.gplayapi)

    // Remote management agent (portal connection)
    implementation("io.socket:socket.io-client:2.1.1") {
        exclude(group = "org.json", module = "json")
    }
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
