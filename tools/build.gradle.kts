// JVM-утилиты подготовки контента: импорт OMAP, курсов, SRTM-горизонталей.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":core"))
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    // Тесты на настоящей карте строят растры на всю карту.
    maxHeapSize = "3g"
}

// Добыча интересных связок: ./gradlew :tools:mineLegs -Pargs="--candidates 400 --count 40"
tasks.register<JavaExec>("mineLegs") {
    group = "waypoint"
    description = "Отбирает интересные связки на карте из maps/ и пишет <карта>.legs.json"
    mainClass.set("waypoint.tools.legs.MineLegsKt")
    classpath = sourceSets["main"].runtimeClasspath
    workingDir = rootProject.projectDir
    maxHeapSize = "4g"
    args = (project.findProperty("args") as String?)?.split(" ")?.filter { it.isNotBlank() } ?: emptyList()
}
