plugins {
    id("com.android.application") version "8.13.0" apply false
    id("org.jetbrains.kotlin.android") version "2.2.10" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10" apply false
}

System.getenv("LENGUA_REACCION_BUILD_DIR")?.let { externalRoot ->
    subprojects {
        layout.buildDirectory.set(file(externalRoot).resolve(name))
    }
}
