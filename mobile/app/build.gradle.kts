import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
}

android {
    namespace = "dev.localstream.sender"
    compileSdk = 37
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "dev.localstream.sender"
        minSdk = 29
        targetSdk = 37
        versionCode = 2
        versionName = "0.2.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        externalNativeBuild {
            cmake {
                cppFlags += listOf(
                    "-std=c++20",
                    "-fexceptions",
                    "-frtti",
                )
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                )
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            externalNativeBuild {
                cmake.arguments += "-DLOCAL_SENDER_ENABLE_TEST_SEAM=ON"
            }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            externalNativeBuild {
                cmake.arguments += "-DLOCAL_SENDER_ENABLE_TEST_SEAM=OFF"
            }
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

    kotlin {
        compilerOptions {
            jvmTarget = JvmTarget.JVM_17
            allWarningsAsErrors = true
            freeCompilerArgs.add("-Xjvm-default=all")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
        }
    }

    buildFeatures {
        buildConfig = true
    }

    testOptions {
        animationsDisabled = true
    }

    lint {
        abortOnError = true
        checkAllWarnings = true
        warningsAsErrors = true
        checkDependencies = true
        // AGP 9.3.1 is documented and tested against Gradle 9.5.0. A newer Gradle
        // distribution is not evidence that this pinned pair should be changed.
        disable += "AndroidGradlePluginVersion"
    }

    defaultConfig.ndk.abiFilters += setOf("arm64-v8a", "x86_64")
}

dependencies {
    implementation("com.google.zxing:core:3.5.4")

    testImplementation("junit:junit:4.13.2")

    androidTestImplementation("androidx.test:core:1.7.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test:rules:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
}
