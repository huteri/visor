plugins {
    id("visor.android.library")
}

android {
    namespace = "com.macedonga.visor.core.media"
}

dependencies {
    api(project(":core:connection"))
    testImplementation(libs.junit)
}
