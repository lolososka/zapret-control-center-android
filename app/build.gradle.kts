plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.dovecoteescapee.byedpi"
    compileSdk = 34
    ndkVersion = "26.1.10909125"

    defaultConfig {
        // Keep the source namespace for the native JNI bridge, but publish the
        // fork as an independent app that can coexist with ByeDPIAndroid.
        applicationId = "io.github.lolososka.zapretmobile"
        minSdk = 21
        targetSdk = 34
        versionCode = 11
        versionName = "0.2.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters.add("armeabi-v7a")
            abiFilters.add("arm64-v8a")
            abiFilters.add("x86")
            abiFilters.add("x86_64")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            buildConfigField("String", "VERSION_NAME",  "\"${defaultConfig.versionName}\"")

            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            buildConfigField("String", "VERSION_NAME",  "\"${defaultConfig.versionName}-debug\"")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    buildFeatures {
        viewBinding = true
    }
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/licenseAssets"))

    // https://android.izzysoft.de/articles/named/iod-scan-apkchecks?lang=en#blobs
    dependenciesInfo {
        // Disables dependency metadata when building APKs.
        includeInApk = false
        // Disables dependency metadata when building Android App Bundles.
        includeInBundle = false
    }
}

dependencies {
    implementation("androidx.fragment:fragment-ktx:1.8.2")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("com.takisoft.preferencex:preferencex:1.1.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-service:2.8.4")
    implementation("net.java.dev.jna:jna:5.14.0@aar")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}

tasks.register<Exec>("runNdkBuild") {
    group = "build"

    val ndkDir = android.ndkDirectory
    executable = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
        "$ndkDir\\ndk-build.cmd"
    } else {
        "$ndkDir/ndk-build"
    }
    setArgs(listOf(
        "NDK_PROJECT_PATH=build/intermediates/ndkBuild",
        "NDK_LIBS_OUT=src/main/jniLibs",
        "APP_BUILD_SCRIPT=src/main/jni/Android.mk",
        "NDK_APPLICATION_MK=src/main/jni/Application.mk"
    ))

    println("Command: $commandLine")
}

tasks.preBuild {
    dependsOn("runNdkBuild", "prepareLicenseAssets")
}

tasks.register<Copy>("prepareLicenseAssets") {
    into(layout.buildDirectory.dir("generated/licenseAssets/licenses"))
    from(rootProject.file("LICENSE")) { rename { "GPL-3.0.txt" } }
    from(rootProject.file("THIRD_PARTY_NOTICES.md"))
    from("src/main/cpp/byedpi/LICENSE") { rename { "ByeDPI-MIT.txt" } }
    from("src/main/cpp/byedpi/kavl.h") { rename { "KAVL-notice.txt" } }
    from("src/main/jni/hev-socks5-tunnel/License") { rename { "hev-socks5-tunnel-MIT.txt" } }
    from("src/main/jni/hev-socks5-tunnel/src/core/License") { rename { "hev-core-MIT.txt" } }
    from("src/main/jni/hev-socks5-tunnel/third-part/hev-task-system/License") { rename { "hev-task-system-MIT.txt" } }
    from("src/main/jni/hev-socks5-tunnel/third-part/lwip/License") { rename { "lwIP-BSD.txt" } }
    from("src/main/jni/hev-socks5-tunnel/third-part/yaml/License") { rename { "libyaml-MIT.txt" } }
}
