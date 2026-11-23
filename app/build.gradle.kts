import java.util.Properties
import java.io.File

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// 更新源配置：仓库留空则「检查更新」为未配置状态，不会发起任何请求。
val updateRepo: String = (project.findProperty("wildsearch.update.repo") as String?).orEmpty()
val updateApiBase: String =
    (project.findProperty("wildsearch.update.apiBase") as String?)?.takeIf { it.isNotBlank() }
        ?: "https://api.github.com"

val signingPropertiesFile = file(
    System.getenv("WILDSEARCH_SIGNING_PROPERTIES")?.takeIf { it.isNotBlank() }
        ?: "${System.getProperty("user.home")}/.android/wildsearch/release-signing.properties",
)
val releaseSigning = Properties().apply {
    if (signingPropertiesFile.isFile) signingPropertiesFile.inputStream().use { load(it) }
}
val signingStoreFile = releaseSigning.getProperty("storeFile")?.takeIf { it.isNotBlank() }?.let {
    val path = File(it)
    if (path.isAbsolute) path else File(signingPropertiesFile.parentFile, it)
}

android {
    namespace = "com.wildcreator.wildsearch"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.wildcreator.wildsearch"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "1.0.2"

        buildConfigField("String", "UPDATE_REPO", "\"$updateRepo\"")
        buildConfigField("String", "UPDATE_API_BASE", "\"$updateApiBase\"")
    }

    signingConfigs {
        create("release") {
            storeFile = signingStoreFile
            storePassword = releaseSigning.getProperty("storePassword")
            keyAlias = releaseSigning.getProperty("keyAlias")
            keyPassword = releaseSigning.getProperty("keyPassword")
            storeType = releaseSigning.getProperty("storeType", "PKCS12")
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
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

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/versions/9/previous-compilation-data.bin"
        }
    }
}

val validateReleaseSigningConfiguration = tasks.register("validateReleaseSigningConfiguration") {
    doLast {
        if (signingStoreFile?.isFile != true || listOf("storePassword", "keyAlias", "keyPassword")
                .any { releaseSigning.getProperty(it).isNullOrBlank() }) {
            throw GradleException("Release 签名配置缺失或不完整。请配置外部 release-signing.properties 或 WILDSEARCH_SIGNING_PROPERTIES；Debug 构建不需要此配置。")
        }
    }
}

tasks.matching {
    it.name in setOf("packageRelease", "packageReleaseBundle", "bundleRelease", "validateSigningRelease")
}.configureEach {
    dependsOn(validateReleaseSigningConfiguration)
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")

    implementation("androidx.datastore:datastore-preferences:1.1.1")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    implementation("io.coil-kt:coil-compose:2.7.0")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}
