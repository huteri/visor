plugins {
    id("visor.android.library")
}

android {
    namespace = "com.macedonga.visor.core.session"
}

dependencies {
    api(project(":core:protocol"))
    api(project(":core:transport"))
    testImplementation(libs.junit)
    testImplementation("org.mockito:mockito-core:5.14.2")
}
