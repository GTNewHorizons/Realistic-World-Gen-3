
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

tasks.register<JavaExec>("previewPerf") {
    group = "verification"
    description = "Runs the deterministic headless preview sampling benchmark"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.irythill.rwg3.preview.PreviewPerformance")
    systemProperty("java.awt.headless", "true")
    if (project.hasProperty("perfJfr")) {
        jvmArgs("-XX:StartFlightRecording=filename=build/preview-perf.jfr,settings=profile,dumponexit=true")
    }
}

tasks.named<Jar>("jar") {
    manifest {
        attributes["Main-Class"] = "com.irythill.rwg3.preview.PreviewLauncher"
    }
}
