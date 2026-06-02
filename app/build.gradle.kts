import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) load(keystorePropsFile.inputStream())
}

android {
    namespace = "com.slate.launcher"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.slate.launcher"
        minSdk = 26
        targetSdk = 36
        versionCode = 4
        versionName = "1.3"
    }

    signingConfigs {
        // Only declare the release signing config when keystore.properties is present.
        // F-Droid CI (and any contributor build) runs without that file; configuring the
        // `storeFile` from an empty properties map would NPE at configure time. Skipping the
        // config here lets F-Droid build an unsigned release APK which it then signs with
        // its own key, the normal F-Droid flow. Local release builds are unaffected.
        if (keystorePropsFile.exists()) {
            create("release") {
                storeFile = file(keystoreProps["storeFile"] as String)
                storePassword = keystoreProps["storePassword"] as String
                keyAlias = keystoreProps["keyAlias"] as String
                keyPassword = keystoreProps["keyPassword"] as String
            }
        }
    }

    buildTypes {
        release {
            // findByName returns null when the signing config wasn't created (no keystore);
            // assigning null leaves the release APK unsigned for F-Droid's signing step.
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    val appName = groovy.xml.XmlParser().parse(file("src/main/res/values/strings.xml"))
        .children()
        .filterIsInstance<groovy.util.Node>()
        .firstOrNull { it.attribute("name") == "app_name" }
        ?.text()
        ?.lowercase()
        ?: "app"

    applicationVariants.all {
        val variant = this
        variant.outputs.all {
            val output = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
            output.outputFileName = "${appName}_${variant.versionName}_${variant.versionCode}_${variant.buildType.name}.apk"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    // AGP 8.1+ embeds the current git revision into META-INF/version-control-info.textproto.
    // Inside F-Droid's sandbox there is no git context, so their build emits a placeholder
    // ("generate_error_reason: NO_VALID_GIT_FOUND") while our local build emits the real SHA.
    // Two different bytes break reproducible-builds verification. Drop the file on every build
    // so the user-signed APK and the F-Droid-built APK are byte-identical.
    packaging {
        resources {
            excludes += "META-INF/version-control-info.textproto"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_1_8
    }
}

// Keep the bundled in-app privacy policy in lockstep with the canonical repo-root copy.
// Runs before any asset/resource merging so a stale or missing assets/PRIVACY_POLICY.md cannot
// ship in an APK. Declares inputs/outputs explicitly so Gradle's up-to-date check and the
// configuration cache both work correctly; fails fast if the source file is missing rather than
// silently shipping an APK with no in-app privacy policy.
val privacyPolicySource = rootProject.file("PRIVACY_POLICY.md")
val privacyPolicyDest = layout.projectDirectory.file("src/main/assets/PRIVACY_POLICY.md")
val copyPrivacyPolicy by tasks.registering(Copy::class) {
    doFirst {
        check(privacyPolicySource.exists()) {
            "Privacy policy source missing at $privacyPolicySource - cannot build without it."
        }
    }
    from(privacyPolicySource)
    into(privacyPolicyDest.asFile.parentFile)
    inputs.file(privacyPolicySource)
    outputs.file(privacyPolicyDest)
}
tasks.named("preBuild").configure { dependsOn(copyPrivacyPolicy) }

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.flexbox)
    implementation(libs.androidx.biometric)
}
