plugins {
    id("com.android.application") version "8.7.3" apply false
    id("com.android.library") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
}

val externalBuildDirectory = providers.environmentVariable("LOANAGENT_BUILD_DIR").orNull

allprojects {
    dependencyLocking {
        lockAllConfigurations()
    }

    if (externalBuildDirectory != null) {
        val modulePath = if (this == rootProject) "root" else path.removePrefix(":")
        layout.buildDirectory.set(file("$externalBuildDirectory/$modulePath"))
    }
}
