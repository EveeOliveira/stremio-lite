plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.stremiolite"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.stremiolite"
        minSdk = 28
        targetSdk = 33
        versionCode = 2
        versionName = "0.2.0-beta"
        // O HY320 (Amlogic T950S) so roda 32 bits.
        ndk { abiFilters += listOf("armeabi-v7a") }
    }

    signingConfigs {
        // Chave de debug local: suficiente para instalar via adb no projetor.
        getByName("debug")
    }

    buildTypes {
        // Instala ao lado do app normal, com dados separados: testar nao mexe na conta.
        debug { applicationIdSuffix = ".dev" }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    packaging {
        resources.excludes += setOf("META-INF/*.version", "META-INF/**/LICENSE*", "kotlin/**", "DebugProbesKt.bin")
        jniLibs.useLegacyPackaging = false
    }
    lint { abortOnError = false; checkReleaseBuilds = false }
}

val media3 = "1.6.1"

dependencies {
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.coil-kt:coil-base:2.7.0")
    // So o gerador de QR code do controle pelo celular (o R8 remove o resto).
    implementation("com.google.zxing:core:3.5.3")

    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    // Build pronta da extensao decoder-ffmpeg do Media3 (inclui .so armeabi-v7a)
    implementation("org.jellyfin.media3:media3-ffmpeg-decoder:$media3+2")

    testImplementation("junit:junit:4.13.2")
}
