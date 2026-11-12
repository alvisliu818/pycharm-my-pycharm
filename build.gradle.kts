plugins {
    id("java")
    id("org.jetbrains.intellij.platform") version "2.20.1"
}

group = "local.autoclaw"
version = "0.2.4"

repositories {
    // 本项目零外部依赖；仅需 IntelliJ Platform Gradle Plugin 的仓库集。
    // 其中 localPlatformArtifacts() 注册的本地 Ivy 仓库负责解析 localIde:<产品>:<版本>（local() 的关键），
    // 不声明它时解析会回落到全局 init.gradle 的远程镜像并 404（这正是此前 Gradle 路线失败的原因）。
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        // 以本机安装的 PyCharm 2026.3 为编译/打包基准，不在线下载 IDE。
        // 用无空格 junction 指向真实安装目录（带空格路径会让 localIvy 仓库解析异常）
        local("D:/programs/PyCharm2026.3")
        // 2026.3 起 VCS 核心 API（AbstractVcs / ChangeProvider 等）拆进了捆绑插件 com.intellij.platform.vcs
        bundledPlugin("com.intellij.platform.vcs")
    }
    // 2.20.1 对 2026.3 新布局的缺口：local() 的平台 classpath 剔除了 lib 下四个 intellij.platform.vcs.*.jar，
    // 而 bundledPlugin 描述符只涵盖插件自己的 lib/modules，不含这四个（VcsException/Change/ContentRevision 所在）。
    // 显式补进编译类路径（compileOnly 不入产物，运行期由平台类加载器解析）。
    compileOnly(files(
        "D:/programs/PyCharm2026.3/lib/intellij.platform.vcs.core.jar",
        "D:/programs/PyCharm2026.3/lib/intellij.platform.vcs.shared.jar",
        "D:/programs/PyCharm2026.3/lib/intellij.platform.vcs.impl.shared.jar",
        "D:/programs/PyCharm2026.3/lib/intellij.platform.vcs.dvcs.impl.shared.jar",
    ))
}

// 2026.3 平台类是 Java 25 字节码（class 69）：编译/运行必须用 JDK 25
// （gradle.properties 已把 PyCharm 自带 JBR 注册为 toolchain 候选）
java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(25)
}

val toolchainService = extensions.getByType<JavaToolchainService>()

tasks {
    register<JavaExec>("selfTest") {
        group = "verification"
        description = "运行 pure 逻辑冒烟测试（对齐 vscode-my-vscode 的 test/smoke.mjs）"
        mainClass.set("local.autoclaw.mypycharm.PureSelfTest")
        classpath = sourceSets["main"].runtimeClasspath
        javaLauncher = toolchainService.launcherFor {
            languageVersion = JavaLanguageVersion.of(25)
        }
    }
}
