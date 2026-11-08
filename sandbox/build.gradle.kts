// Десктоп-песочница этапа 0: рисование маршрута мышью и разбор связки.
plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":tools"))
}

application {
    mainClass.set("waypoint.sandbox.MainKt")
    applicationDefaultJvmArgs = listOf("-Xmx4g")
}

tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
}
