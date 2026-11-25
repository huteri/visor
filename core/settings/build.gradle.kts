plugins {
    id("visor.android.library")
}

android {
    namespace = "com.macedonga.visor.core.settings"
}

dependencies {
    api(project(":core:connection"))
    implementation(libs.flatbuffers.java)
}
