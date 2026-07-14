plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.loanagent.agent"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.loanagent.agent"
        minSdk = 26
        targetSdk = 35
        versionCode = 6
        versionName = "0.1.5"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16.1")
}

tasks.withType<Test>().configureEach {
    val robolectricTemp = layout.buildDirectory.dir("robolectric-tmp")
    doFirst {
        robolectricTemp.get().asFile.mkdirs()
    }
    systemProperty("java.io.tmpdir", robolectricTemp.get().asFile.absolutePath)
}
