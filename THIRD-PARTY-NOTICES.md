# 第三方组件与许可（THIRD-PARTY-NOTICES）

本项目的**自有代码**按 **Apache License 2.0** 发布（见 `LICENSE`）。
本文件列出**随程序一起分发**的第三方组件及其许可 —— 它们会被打进
`mvnw -Pdist package` 产出的 app image（`target/dist/TalkingLive/app/lib/*.jar`），
所以必须跟着交付物一起被声明。

| 组件 | 版本 | 许可 | 说明 |
|---|---|---|---|
| Vosk（Java 绑定 + 原生库）`com.alphacephei:vosk` | 0.3.45 | Apache-2.0 | 离线语音识别引擎。注意本项目**不用**官方 `org.vosk` 绑定（中文会崩在原生层，见 `DESIGN.md` 附录 B） |
| JNA / JNA Platform `net.java.dev.jna:jna` `jna-platform` | 5.14.0 | **Apache-2.0 OR LGPL-2.1**（双许可；本项目按 Apache-2.0 使用） | Win32 互操作：`SendInput` 注入、窗口样式、前台窗口监听 |
| FlatLaf `com.formdev:flatlaf` | 3.4.1 | Apache-2.0 | 设置窗口的浅色主题 |
| SLF4J API `org.slf4j:slf4j-api` | 2.0.13 | MIT（Copyright (c) 2004-2022 QOS.ch Sarl, Switzerland） | 日志门面 |
| Logback `ch.qos.logback:logback-classic` `logback-core` | 1.5.6 | **EPL-1.0 或 LGPL-2.1** | 日志实现。这两个 jar **内部没有**带许可文件，因此在这里显式声明 |
| JUnit 5 `org.junit.jupiter:junit-jupiter` | 5.10.2 | EPL-2.0 | **仅测试期**，不随交付物分发 |

## 语音模型（不进本仓库、也不由本仓库再分发）

小模型与大模型都**不在版本库里**，也**不由本仓库分发**：用户按 `README.md` 的
[三步开始](../README.md) 从上游下载（Alpha Cephei 官方站或其镜像），程序只是去
`%LOCALAPPDATA%\TalkingLive\models\` 找它。

两个模型的许可都是 **Apache-2.0**（来源：[Vosk models 官方列表](https://alphacephei.com/vosk/models)）：

| 模型 | 大小 | 许可 |
|---|---|---|
| `vosk-model-small-cn-0.22` | 42 MB | Apache-2.0 |
| `vosk-model-cn-0.22` | 1.3 GB | Apache-2.0 |

> ⚠️ 若将来改成**把模型打进安装包**（`DECISIONS.md` 里的方案 C 目前是"首次运行由用户下载"），
> 那一步必须**同时**更新本文件与 `NOTICE`，把模型的署名与许可一并声明 —— 分发别人的东西就得替它声明。

## 怎么复核这些声明（别信手抄）

- 上面每条都能从**本地 jar** 复核：`flatlaf` / `jna` / `jna-platform` / `slf4j-api` 的
  `META-INF/LICENSE*` 就在 jar 里（`unzip -p target/lib/flatlaf-3.4.1.jar META-INF/LICENSE`）。
- `logback-*` 与 `vosk` 的 jar **没有**许可文件，声明以各自项目主页为准。
- 版本号来自 `pom.xml` 的 `<properties>`（`vosk.version` / `jna.version` / `flatlaf.version` /
  `slf4j.version` / `logback.version` / `junit.version`）——
  `ThirdPartyNoticesTest` 会**扫这两个文件**，一旦某个运行时依赖的版本没写进本表就让构建失败，
  免得这份声明悄悄过期（手写常量必然过期，本项目在别处已经栽过）。
