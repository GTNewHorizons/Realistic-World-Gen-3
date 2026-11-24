
plugins {
    id("com.gtnewhorizons.gtnhconvention")
}

tasks.register<JavaExec>("runPreview") {
    group = "application"
    description = "Runs the standalone RWG3 world-planner preview"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.irythill.rwg3.preview.PreviewLauncher")
}

tasks.register<JavaExec>("previewSmoke") {
    group = "verification"
    description = "Generates and renders every planner stage without opening a window"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.irythill.rwg3.preview.PreviewLauncher")
    args("--smoke")
    systemProperty("java.awt.headless", "true")
}

tasks.named<Jar>("jar") {
    manifest {
        attributes["Main-Class"] = "com.irythill.rwg3.preview.PreviewLauncher"
    }
}
