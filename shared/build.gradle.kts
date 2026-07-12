import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    // AGP 9 requires the built-in KMP Android library plugin, which registers the Android target
    // via this `androidLibrary` block. The Android app (com.android.application) consumes this
    // module's Android variant, which carries the whole Compose Multiplatform UI, so the exact same
    // screens render on device.
    androidLibrary {
        namespace = "com.queatz.microcosmicdisambiguation.shared"
        compileSdk = 36
        minSdk = 31
    }

    // The browser (Kotlin/Wasm) target hosts the identical Compose UI via the canvas renderer.
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
        binaries.executable()
    }

    sourceSets {
        commonMain.dependencies {
            // Compose Multiplatform: the shared UI toolkit. On Android these map to the AndroidX
            // Compose artifacts, so the very same composables run natively on device and in the
            // browser.
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.animation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(compose.components.resources)
            implementation(compose.materialIconsExtended)
            implementation(libs.jetbrains.lifecycle.viewmodel.compose)

            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
        }
        androidMain.dependencies {
            // OkHttp powers the Ktor engine (picked up automatically) and the ASR WebSocket.
            implementation(libs.ktor.client.okhttp)
            // Needed by the Android `actual` implementations (permission launcher, dynamic color).
            implementation(libs.androidx.activity.compose)
            implementation(libs.androidx.core.ktx)
        }
        wasmJsMain.dependencies {
            // The Ktor JS/fetch engine (published for wasmJs) and the browser DOM bindings.
            implementation(libs.ktor.client.js)
            implementation(libs.kotlinx.browser)
        }
    }
}

compose.resources {
    // Generate a public `Res` class with a stable package so the shared UI can reference strings
    // (and their per-language translations) identically on every platform.
    publicResClass = true
    generateResClass = always
    packageOfResClass = "com.queatz.microcosmicdisambiguation.resources"
}
