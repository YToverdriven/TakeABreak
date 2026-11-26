import java.io.File
import java.util.Properties

plugins {
    id("com.android.application")
}

android {
    namespace = "com.takeabreak.controller"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.takeabreak.controller"
        minSdk = 26
        targetSdk = 34
        versionCode = 130
        versionName = "0.13.0"
    }

    val keystorePropsFile = rootProject.file("keystore.properties")
    val keystoreProps = Properties().apply {
        if (keystorePropsFile.isFile) {
            keystorePropsFile.inputStream().use { load(it) }
        }
    }
    val releaseStorePath = keystoreProps.getProperty("storeFile")?.trim().orEmpty()
    val releaseStoreFile = releaseStorePath
        .takeIf { it.isNotEmpty() }
        ?.let { path -> File(path).takeIf { it.isAbsolute } ?: rootProject.file(path) }
    val releaseStorePassword = keystoreProps.getProperty("storePassword")?.trim().orEmpty()
    val releaseKeyAlias = keystoreProps.getProperty("keyAlias")?.trim().orEmpty()
    val releaseKeyPassword = keystoreProps.getProperty("keyPassword")?.trim().orEmpty()
    val hasReleaseSigning = (releaseStoreFile != null) &&
        releaseStoreFile.isFile &&
        releaseStorePassword.isNotEmpty() &&
        releaseKeyAlias.isNotEmpty() &&
        releaseKeyPassword.isNotEmpty()

    val projectDebugKeystore = file("${rootDir}/debug.keystore")
    signingConfigs {
        if (projectDebugKeystore.exists()) {
            create("debugConfig") {
                storeFile = projectDebugKeystore
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
        if (hasReleaseSigning) {
            create("release") {
                storeFile = releaseStoreFile
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            if (projectDebugKeystore.exists()) {
                signingConfig = signingConfigs.getByName("debugConfig")
            }
        }
        release {
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.work:work-runtime-ktx:2.9.0")
}
