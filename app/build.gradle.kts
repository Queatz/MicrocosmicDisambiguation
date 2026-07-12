plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.legacy.kapt)
    alias(libs.plugins.objectbox)
}

android {
    namespace = "com.queatz.microcosmicdisambiguation"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.queatz.microcosmicdisambiguation"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }

    // Compose Multiplatform resource packaging workaround (AGP 9 + com.android.kotlin.multiplatform.library).
    // That new KMP Android-library plugin does not yet copy the shared module's Compose resources
    // into the consuming app, so at runtime the generated `Res` accessors can't find the strings
    // (the Android resource reader looks them up via the classloader / Java resources). We copy the
    // prepared resources into a generated Java-resources dir under the generated Res package path so
    // they are packaged into the APK.
    sourceSets.getByName("main").resources
        .srcDir(layout.buildDirectory.dir("generated/composeResourcesJavaRes").get().asFile)
}

val copyComposeResourcesToJavaRes by tasks.registering(Copy::class) {
    dependsOn(":shared:prepareComposeResourcesTaskForCommonMain")
    from(
        project(":shared").layout.buildDirectory.dir(
            "generated/compose/resourceGenerator/preparedResources/commonMain/composeResources",
        ),
    )
    // The runtime looks resources up under `composeResources/<Res package>/…`, so insert that
    // package directory (matching `packageOfResClass` in the shared module) here.
    into(
        layout.buildDirectory.dir(
            "generated/composeResourcesJavaRes/composeResources/com.queatz.microcosmicdisambiguation.resources",
        ),
    )
}

// Ensure the resources are copied before the Java-resources source set is consumed (both the
// per-variant `process*JavaRes` and the packaging `merge*JavaResource` tasks), for debug and release.
tasks.matching {
    it.name.matches(Regex("(process|merge).*JavaRes(ource)?"))
}.configureEach {
    dependsOn(copyComposeResourcesToJavaRes)
}

dependencies {
    // The whole Compose Multiplatform UI (screens, theme, navigation, view model) lives in the
    // shared module and is reused verbatim here — this is the same UI the web target renders.
    implementation(project(":shared"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    // Only needed to host the shared UI: `setContent` + edge-to-edge in MainActivity. The Compose
    // runtime/material/foundation artifacts are pulled in transitively from :shared (Compose
    // Multiplatform), so the app doesn't declare its own Compose BOM anymore.
    implementation(libs.androidx.activity.compose)

    // The Android speech recognizer streams microphone audio over an OkHttp WebSocket, and the
    // Qwen client uses Ktor's OkHttp engine.
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.client.logging)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.objectbox.kotlin)
    implementation(libs.objectbox.android)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
