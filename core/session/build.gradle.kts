plugins {
    id("visor.android.library")
}

android {
    namespace = "com.macedonga.visor.core.session"
}

dependencies {
    // The whole of this module's repository dependencies.
    api(project(":core:protocol"))
    api(project(":core:transport"))
    testImplementation(libs.junit)
}
