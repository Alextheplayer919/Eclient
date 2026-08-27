import java.util.concurrent.TimeUnit

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace  = "com.rubidiumclient"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.rubidiumclient"
        minSdk        = 26
        targetSdk     = 35
        versionCode   = 3
        versionName   = "2.1.0"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    signingConfigs {
        create("release") {
            storeFile     = System.getenv("KEYSTORE_PATH")?.takeIf { it.isNotBlank() }?.let { file(it) } ?: file("debug.jks")
            storePassword = System.getenv("KEYSTORE_PASS") ?: "rubidiumclient"
            keyAlias      = System.getenv("KEY_ALIAS")     ?: "rubidiumclient"
            keyPassword   = System.getenv("KEY_PASS")      ?: "rubidiumclient"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled   = true
            isShrinkResources = true
            signingConfig     = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled     = false
            applicationIdSuffix = ".debug"
            versionNameSuffix   = "-DEBUG"
        }
    }

    compileOptions {
        sourceCompatibility            = JavaVersion.VERSION_17
        targetCompatibility            = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose     = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += listOf(
                "META-INF/INDEX.LIST",
                "META-INF/io.netty.versions.properties",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
                "META-INF/versions/**",
                "META-INF/native-image/**",
                "META-INF/proguard/**",
                "google/protobuf/**",
                "DebugProbesKt.bin"
            )
        }
        jniLibs {
            excludes += listOf("**/libnetty_transport_native_epoll*.so")
        }
    }
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.0.20")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.3")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    val bom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(bom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.foundation:foundation")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.savedstate:savedstate-ktx:1.2.1")
    implementation("androidx.media:media:1.7.0")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.11.0")

    implementation("org.cloudburstmc.protocol:bedrock-connection:3.0.0.Beta6-SNAPSHOT") {
        exclude(group = "io.netty", module = "netty-transport-native-epoll")
        exclude(group = "io.netty", module = "netty-transport-native-kqueue")
        exclude(group = "io.netty.incubator", module = "netty-incubator-transport-native-io_uring")
        exclude(group = "it.unimi.dsi", module = "fastutil-core")
    }
    implementation("org.cloudburstmc.protocol:bedrock-codec:3.0.0.Beta6-SNAPSHOT") {
        exclude(group = "it.unimi.dsi", module = "fastutil-core")
    }
    implementation("org.cloudburstmc:nbt:3.0.0.Final")

    implementation("io.netty:netty-transport:4.1.111.Final")
    implementation("io.netty:netty-codec:4.1.111.Final")
    implementation("io.netty:netty-handler:4.1.111.Final")
    implementation("io.netty:netty-buffer:4.1.111.Final")

    implementation("org.bitbucket.b_c:jose4j:0.9.6")

    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.4")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}

configurations.all {
    resolutionStrategy.cacheChangingModulesFor(0, TimeUnit.SECONDS)
    resolutionStrategy.force("org.cloudburstmc.fastutil:core:8.5.15")
}
