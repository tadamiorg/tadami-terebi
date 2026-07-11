plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.sf.tadami.terebi"

    defaultConfig {
        applicationId = "com.sf.tadami.terebi"
        versionCode = 3
        versionName = "1.0.2"
    }

    signingConfigs {
        create("release"){
            storeFile = file(env.SIGNING_KEY.value)
            storePassword = env.KEY_STORE_PASSWORD.value
            keyAlias = env.ALIAS.value
            keyPassword = env.KEY_STORE_PASSWORD.value
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-DEBUG"
            isDebuggable = true
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    packaging {
        resources {
            excludes.add("/META-INF/{AL2.0,LGPL2.1}")
            excludes.add("META-INF/INDEX.LIST")
        }
    }
}

dependencies {
    implementation(libs.core.ktx)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.process)
    implementation(libs.lifecycle.viewmodel.compose)

    // Compose for TV
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.material3)
    implementation(libs.tv.material)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.compose.ui.tooling.preview)

    // Media3 / ExoPlayer + MediaSession bridge for Cast transport controls
    implementation(libs.bundles.exoplayer)
    implementation(libs.media3.datasource.okhttp)

    // Cast Connect (Android TV receiver)
    implementation(libs.cast.tv)
    implementation(libs.cast.core)
    implementation(libs.play.services.basement)

    implementation(libs.serialization.json)
    implementation(libs.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.coil.compose)

    // Installs the baseline profile (app + merged library profiles) so ART AOT-compiles hot paths.
    implementation(libs.androidx.profileinstaller)
}
