plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

val appVersionCode = (project.findProperty("appVersionCode") as String?)?.toInt() ?: 1
val appVersionName = (project.findProperty("appVersionName") as String?) ?: "0.1.0"

// Donation link (Ko-fi/Buy Me a Coffee). Google Play does not allow donation links to external payment
// services for individual developers, so the Play build (-PplayBuild=true, used for the AAB) never has one.
val playBuild = (project.findProperty("playBuild") as String?)?.toBoolean() ?: false
val donationUrl = if (playBuild) "" else (project.findProperty("donationUrl") as String?).orEmpty().trim()

android {
    namespace = "com.somecatcode.ebookreader"
    compileSdk = 36
    // NDK only for llvm-objcopy: AGP strips the AndroidX .so files and extracts their symbol tables for Play.
    // CI installs exactly this version (the workflows read it from here).
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "com.somecatcode.ebookreader"
        minSdk = 26
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "DONATION_URL", "\"" + donationUrl.replace("\"", "") + "\"")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // Release signing is only configured when the keystore is provided via environment variables (CI).
    val keystorePath: String? = System.getenv("ANDROID_KEYSTORE_PATH")
    if (!keystorePath.isNullOrBlank()) {
        signingConfigs {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfigs.findByName("release")?.let { signingConfig = it }
            // native libs come only from AndroidX (graphics path, DataStore); ship their symbol tables so
            // Play can symbolicate crashes and ANRs (ends up in BUNDLE-METADATA of the AAB)
            ndk { debugSymbolLevel = "SYMBOL_TABLE" }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        lintConfig = file("lint.xml")
        abortOnError = true
        warningsAsErrors = false
        checkReleaseBuilds = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    // Room schema export (migration tests read the JSON files from here).
    sourceSets {
        getByName("test").assets.directories.add("$projectDir/schemas")
        // privacy policy texts (docs/privacy) shown in the app, see copyPrivacyPolicy
        getByName("main").assets.directories.add(layout.buildDirectory.dir("generated/privacyAssets").get().asFile.path)
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

// ---- reader-web: Vite bundle built from reader-core of the server repo (git submodule) ----------
val readerWebDir = rootProject.layout.projectDirectory.dir("reader-web")
val readerAssetsDir = layout.projectDirectory.dir("src/main/assets/reader")
val isWindows = System.getProperty("os.name").lowercase().contains("windows")
val skipReaderWeb = project.hasProperty("skipReaderWeb")
val npmCmd = if (isWindows) "npm.cmd" else "npm"

val npmCi = tasks.register<Exec>("readerWebNpmCi") {
    enabled = !skipReaderWeb
    workingDir = readerWebDir.asFile
    commandLine(npmCmd, "ci", "--no-audit", "--no-fund")
    inputs.files(readerWebDir.file("package.json"), readerWebDir.file("package-lock.json"))
    outputs.dir(readerWebDir.dir("node_modules"))
}

val buildReaderWeb = tasks.register<Exec>("buildReaderWeb") {
    group = "build"
    description = "Builds reader-web (npm ci + npm run build) into src/main/assets/reader. Skip with -PskipReaderWeb."
    enabled = !skipReaderWeb
    dependsOn(npmCi)
    workingDir = readerWebDir.asFile
    commandLine(npmCmd, "run", "build")
    inputs.files(
        readerWebDir.file("package.json"), readerWebDir.file("package-lock.json"),
        readerWebDir.file("vite.config.ts"), readerWebDir.file("tsconfig.json"), readerWebDir.file("index.html"),
    )
    inputs.dir(readerWebDir.dir("src"))
    inputs.files(fileTree(rootProject.layout.projectDirectory.dir("third_party/nextcloud_ebook_reader/packages/reader-core")))
    outputs.dir(readerAssetsDir)
}

// ---- privacy policy: docs/privacy/*.md -> assets/privacy/<language>.md (one source for repo and app) ----
val copyPrivacyPolicy = tasks.register<Copy>("copyPrivacyPolicy") {
    val docs = rootProject.layout.projectDirectory.dir("docs/privacy")
    from(docs.file("datenschutz.md")) { rename { "de.md" } }
    from(docs.file("privacy-policy.md")) { rename { "en.md" } }
    from(docs.file("politica-de-privacidad.md")) { rename { "es.md" } }
    from(docs.file("privacy-policy-ja.md")) { rename { "ja.md" } }
    from(docs.file("politique-de-confidentialite.md")) { rename { "fr.md" } }
    from(docs.file("privacy-policy-ar.md")) { rename { "ar.md" } }
    from(docs.file("gizlilik-politikasi.md")) { rename { "tr.md" } }
    from(docs.file("privacy-policy-fa-AF.md")) { rename { "fa-AF.md" } }
    into(layout.buildDirectory.dir("generated/privacyAssets/privacy"))
}

tasks.named("preBuild") { dependsOn(buildReaderWeb, copyPrivacyPolicy) }

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.webkit)

    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.androidx.work.testing)
}
