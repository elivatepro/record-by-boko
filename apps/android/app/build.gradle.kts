import java.util.Properties
import org.openapitools.generator.gradle.plugin.tasks.GenerateTask

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.openapi.generator)
}

// Per-machine settings live in local.properties (git-ignored). See README.md.
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use(::load)
}

fun setting(name: String, default: String): String =
    providers.environmentVariable(name.uppercase().replace('.', '_')).orNull
        ?: localProperties.getProperty(name)
        ?: default

android {
    namespace = "com.boko.roudio"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.boko.roudio"
        minSdk = 29
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"

        // The backend URL. With a phone on USB, `adb reverse tcp:8000 tcp:8000` makes this work.
        buildConfigField("String", "API_URL", "\"${setting("roudio.apiUrl", "http://127.0.0.1:8000")}\"")
        // The *web/backend* OAuth client ID: Google issues ID tokens and auth codes for it.
        buildConfigField(
            "String",
            "GOOGLE_SERVER_CLIENT_ID",
            "\"${setting("roudio.googleServerClientId", "")}\"",
        )
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

}

// Kotlin API client generated from packages/api-spec/openapi.json on every build.
val generateApiClient by tasks.registering(GenerateTask::class) {
    generatorName.set("kotlin")
    library.set("jvm-retrofit2")
    // A file:// URI, because the repo path may contain spaces.
    inputSpec.set(rootProject.file("../../packages/api-spec/openapi.json").toURI().toString())
    outputDir.set(layout.buildDirectory.dir("generated/openapi").get().asFile.path)
    packageName.set("com.boko.roudio.api")
    generateApiTests.set(false)
    generateModelTests.set(false)
    generateApiDocumentation.set(false)
    generateModelDocumentation.set(false)
    configOptions.set(
        mapOf(
            "serializationLibrary" to "kotlinx_serialization",
            "useCoroutines" to "true",
            "omitGradleWrapper" to "true",
            "enumPropertyNaming" to "UPPERCASE",
        ),
    )
}

tasks.named("preBuild") { dependsOn(generateApiClient) }

androidComponents {
    onVariants { variant ->
        variant.sources.kotlin?.addStaticSourceDirectory(
            layout.buildDirectory.dir("generated/openapi/src/main/kotlin").get().asFile.path,
        )
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play)
    implementation(libs.googleid)
    implementation(libs.play.services.auth)

    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.retrofit.scalars)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.kotlinx.coroutines.android)

    // Plaud SDK (app/libs/README.md) and the libraries it needs at runtime. Timber is replaced
    // by a silent copy in src/main/java/timber, so the SDK can't log secrets.
    implementation(files("libs/plaud-sdk.aar"))
    implementation(libs.plaud.gson)
    implementation(libs.plaud.retrofit.gson)
    implementation(libs.plaud.java.websocket)
    implementation(libs.plaud.bouncycastle)
    implementation(libs.plaud.guava)
    // Only used when the phone lacks ChaCha20-Poly1305 (the SDK installs it as a provider then).
    implementation(libs.plaud.conscrypt)
    implementation(libs.plaud.logback.android)
    implementation(libs.plaud.slf4j)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
