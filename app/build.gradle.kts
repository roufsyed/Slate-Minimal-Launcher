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
        versionCode = 2
        versionName = "1.1"
    }

    signingConfigs {
        create("release") {
            storeFile = file(keystoreProps["storeFile"] as String)
            storePassword = keystoreProps["storePassword"] as String
            keyAlias = keystoreProps["keyAlias"] as String
            keyPassword = keystoreProps["keyPassword"] as String
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
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
