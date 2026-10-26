import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    // AGP 9 integra il supporto Kotlin: non serve più org.jetbrains.kotlin.android
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// Firma release opzionale: keystore.properties (locale) oppure variabili d'ambiente (CI).
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun signingValue(key: String, env: String): String? =
    keystoreProps.getProperty(key) ?: System.getenv(env)

android {
    namespace = "com.mauztube"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.mauztube"
        minSdk = 24
        targetSdk = 37
        versionCode = 4
        versionName = "1.0.3"
        vectorDrawables.useSupportLibrary = true
    }

    signingConfigs {
        val storePath = signingValue("storeFile", "MAUZTUBE_KEYSTORE")
        if (storePath != null) {
            create("release") {
                storeFile = file(storePath)
                storePassword = signingValue("storePassword", "MAUZTUBE_KEYSTORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "MAUZTUBE_KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "MAUZTUBE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        // Build ottimizzata ma NON offuscata: serve solo a registrare il Baseline Profile
        // (i nomi di classi e metodi devono essere quelli originali).
        create("profiling") {
            initWith(getByName("release"))
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Firma: keystore personale se configurato. Altrimenti l'APK resta NON firmato (è ciò
            // che serve a F-Droid, che lo firma da sé); con -PdebugSign si firma con la chiave di
            // debug, per chi compila un fork senza keystore e vuole installarlo subito.
            signingConfig = signingConfigs.findByName("release")
                ?: if (project.hasProperty("debugSign")) signingConfigs.getByName("debug") else null
        }
    }

    // Niente blocco "dependency metadata" nell'APK: è cifrato con una chiave di Google, non
    // verificabile, e F-Droid / IzzyOnDroid lo rifiutano.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    compileOptions {
        // NewPipeExtractor usa java.nio/java.time: necessario il desugaring per minSdk < 26
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/DEPENDENCIES")
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs.nio)

    implementation(libs.androidx.core.ktx)
    // Installa i Baseline Profile di Compose anche su APK sideloaded: avvio e scroll più fluidi.
    implementation(libs.androidx.profileinstaller)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.exoplayer.dash)
    implementation(libs.androidx.media3.datasource.okhttp)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.session)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.newpipe.extractor)
    // Codificatore MP3 in Java puro (porting di LAME): niente librerie native.
    implementation(libs.jump3r)
}
