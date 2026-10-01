import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "io.github.wolverr.bivouac"
    compileSdk = 36
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "io.github.wolverr.bivouac"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DCMAKE_BUILD_TYPE=Release",
                    "-DBUILD_SHARED_LIBS=ON",
                    "-DLLAMA_BUILD_COMMON=ON",
                    "-DLLAMA_BUILD_TESTS=OFF",
                    "-DLLAMA_BUILD_EXAMPLES=OFF",
                    "-DLLAMA_BUILD_TOOLS=OFF",
                    "-DLLAMA_BUILD_SERVER=OFF",
                    "-DLLAMA_OPENSSL=OFF",
                    "-DLLAMA_CURL=OFF",
                    "-DGGML_NATIVE=OFF",
                    // One CPU backend per ARM feature level (v8.0 ... v9 i8mm/SVE),
                    // picked at runtime so the same APK is fast on old and new phones.
                    "-DGGML_BACKEND_DL=ON",
                    "-DGGML_CPU_ALL_VARIANTS=ON",
                    "-DGGML_LLAMAFILE=OFF",
                    // We pin our own ggml threadpool to the big cores instead of OpenMP.
                    "-DGGML_OPENMP=OFF",
                )
            }
        }
    }

    // Release key from a properties file outside the repo (BIVOUAC_KEYSTORE, default
    // ~/bivouac-release/keystore.properties: storeFile, storePassword, keyAlias, keyPassword).
    // Without it, release builds are signed with the debug key so anyone can build from source.
    val keystoreProps = file(System.getenv("BIVOUAC_KEYSTORE") ?: "${System.getProperty("user.home")}/bivouac-release/keystore.properties")
    if (keystoreProps.exists()) {
        val p = Properties().apply { keystoreProps.inputStream().use { load(it) } }
        signingConfigs.create("release") {
            storeFile = file(p.getProperty("storeFile"))
            storePassword = p.getProperty("storePassword")
            keyAlias = p.getProperty("keyAlias")
            keyPassword = p.getProperty("keyPassword")
        }
    }

    buildTypes {
        debug {
            // The native code is always built optimized; an -O0 llama.cpp is unusably slow.
            externalNativeBuild { cmake { arguments += "-DCMAKE_BUILD_TYPE=Release" } }
        }
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    externalNativeBuild {
        cmake {
            path("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
        }
    }

    packaging {
        // ggml loads its CPU backend variants with dlopen() from nativeLibraryDir,
        // so the .so files must be extracted at install time.
        jniLibs { useLegacyPackaging = true }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
}
