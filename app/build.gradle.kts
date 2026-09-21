import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

/**
 * Release signing credentials, read from `keystore/keystore.properties` â€?which is gitignored,
 * because the key file and its password must never enter version control.
 *
 * Loaded optionally, and that is the important part: without the file the release variant stays
 * **unsigned** instead of failing the build. A contributor with no key can still run
 * `assembleRelease` to check that R8 and the shrinking rules work, which is exactly the check
 * that catches serialization-proguard mistakes. Only publishing needs the key.
 */
val releaseSigning: Properties? = rootProject.file("keystore/keystore.properties")
    .takeIf { it.exists() }
    ?.let { file -> Properties().apply { file.inputStream().use { load(it) } } }

android {
    namespace = "com.ranorac.tjtimetable"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.ranorac.tjtimetable"
        minSdk = 26 // java.time on the platform, no desugaring needed
        targetSdk = 35
        versionCode = 20204
        versionName = "2.2.4"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        if (releaseSigning != null) {
            create("release") {
                // `storeFile` in the properties is written relative to the repository root, so
                // it is resolved against `rootProject` rather than the `app` module.
                storeFile = rootProject.file(releaseSigning.getProperty("storeFile"))
                storePassword = releaseSigning.getProperty("storePassword")
                keyAlias = releaseSigning.getProperty("keyAlias")
                keyPassword = releaseSigning.getProperty("keyPassword")
                // v1 is kept alongside v2/v3: it costs nothing and it is what lets an APK
                // install on an odd OEM build (or an old tool) that ignores the newer schemes.
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Null when no keystore: the variant is then built but left unsigned.
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests {
            // Robolectric needs the merged resources to inflate the app's theme and
            // strings when it renders a composable on the host JVM.
            isIncludeAndroidResources = true
        }
    }

    /**
     * Publish the exported Room schemas as **test assets**.
     *
     * `MigrationTestHelper` reads the historical schema from the assets folder, so without this
     * the upgrade test cannot even describe what the previous release created â€?and the upgrade
     * path is exactly what shipped broken once (`day_adjustments` gained a primary key while the
     * database version stayed put, so every existing install crashed on launch).
     */
    sourceSets {
        getByName("test") {
            assets.srcDir("$projectDir/schemas")
        }
    }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "/META-INF/DEPENDENCIES",
        )
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

/**
 * Run Robolectric fully offline, with its scratch state inside the workspace.
 *
 * Two separate constraints, both hit only under a confined build sandbox â€?and both of which
 * *look* like failing tests rather than an environment restriction:
 *
 *  1. Robolectric takes a lock at **`$user.home/.robolectric-download-lock`** (hard-coded, no
 *     property exists for it). With the user profile unwritable, *every* Robolectric test dies
 *     while loading its SDK jar with `Couldn't create lock file`.
 *  2. Its Maven resolver then tries to *fetch* `android-all-instrumented` even though the jar is
 *     already in the local repository, and the fetch fails.
 *
 * Fixing both: the forked test JVM gets a workspace `user.home`, and `robolectric-deps.properties`
 * â€?the resolver's own offline mechanism â€?maps the SDK level straight at the cached jar, so no
 * network access and no Maven lock are involved at all. Only the test JVM is affected; the Gradle
 * daemon keeps the real home.
 *
 * The properties file is generated rather than committed because it contains an absolute path.
 */
val roboHome = rootProject.layout.projectDirectory.dir(".robolectric-home").asFile
val roboDeps = roboHome.resolve("robolectric-deps.properties")
val roboJars = roboHome.resolve("jars")
val androidAll = File(
    System.getProperty("user.home"),
    "/.m2/repository/org/robolectric/android-all-instrumented",
)

tasks.withType<Test>().configureEach {
    doFirst {
        roboHome.mkdirs()
        roboJars.mkdirs()
        // Each cached SDK level, hard-linked (or copied) into a workspace directory and mapped
        // for Robolectric by its `groupId:artifactId:version` short name. Robolectric's offline
        // resolver only accepts a jar it can reach without touching Maven, and a path outside
        // the workspace is not reliably readable from the confined test JVM.
        val versions = androidAll.listFiles()
            ?.filter { it.isDirectory }
            ?.mapNotNull { dir ->
                val jar = dir.listFiles()?.firstOrNull { it.extension == "jar" } ?: return@mapNotNull null
                dir.name to jar
            }
            ?.sortedBy { it.first }
            .orEmpty()
        roboDeps.writeText(
            buildString {
                appendLine("# Generated by app/build.gradle.kts â€?do not edit, do not commit.")
                appendLine("# Maps Robolectric SDK levels onto the android-all jars already cached")
                appendLine("# in the local Maven repository, so the test suite needs no network.")
                for ((version, source) in versions) {
                    val local = File(roboJars, source.name)
                    if (!local.exists() || local.length() != source.length()) {
                        source.copyTo(local, overwrite = true)
                    }
                    // The key is `DependencyJar.getShortName()` â€?`groupId:artifactId:version` â€?                    // and those colons MUST be backslash-escaped: in a .properties file a colon
                    // is a key/value separator, so an unescaped key silently loads as three
                    // unrelated keys and every lookup misses.
                    val key = "org.robolectric:android-all-instrumented:$version".replace(":", "\\:")
                    appendLine("$key=${local.absolutePath.replace('\\', '/')}")
                }
            },
            Charsets.UTF_8,
        )
    }
    systemProperty("user.home", roboHome.absolutePath)
    // The resolver's own offline switch: `LegacyDependencyResolver` reads this exact property
    // name to load a flat key->jar map instead of touching Maven at all.
    systemProperty("robolectric-deps.properties", roboDeps.absolutePath)
    systemProperty("java.io.tmpdir", roboHome.absolutePath)
    // Robolectric's legacy SQLite shadow extracts a native `sqlite4java` library into a temp
    // directory at runtime, which a confined sandbox blocks ("Cannot extract SQLite library").
    // NATIVE mode uses the real SQLite, needs no extraction, and is the more faithful choice for
    // migration tests anyway â€?real SQL semantics rather than an emulated subset.
    systemProperty("robolectric.sqliteMode", "NATIVE")
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)

    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)

    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core.ktx)
    testImplementation(libs.androidx.room.testing)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.compose.bom))

    debugImplementation(libs.compose.ui.tooling)
}
