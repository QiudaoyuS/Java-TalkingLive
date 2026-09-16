# TalkingLive

常驻后台的 Windows 语音输入工具。说唤醒词后，你讲的话会实时出现在**任意应用**的光标处。

- 产品设计：`docs/DESIGN.md`
- 技术方案：`docs/TECH-PLAN.md`（与设计冲突时以技术方案为准）
- 交互原型：`demo/`（已完成的 P0，纯模拟数据）

## 构建

需要 JDK 21。Maven 用 wrapper，无需单独安装。

```
mvnw.cmd package        # 编译 + 跑全部单测
mvnw.cmd test           # 只跑单测
mvnw.cmd -Pdist package # M5：jpackage 产出带运行时的 app image（target/dist）
```

> ⚠️ `JAVA_HOME` 必须指向 JDK 21（`D:\Code\Java\jdk-21.0.12.1`）。
> Maven Wrapper 优先读 `JAVA_HOME`，指向 JDK 8 会直接构建失败。

## 运行

```
mvnw.cmd package
java -jar target\talkinglive.jar              # 常驻后台，桌面上只有一颗悬浮球
java -jar target\talkinglive.jar --settings   # 启动并打开设置窗口
java -jar target\talkinglive.jar --doctor     # 环境自检：模型 / 麦克风 / 注入能力
```

## 模型

模型不进版本库，统一放在 `%LOCALAPPDATA%\TalkingLive\models\`。见 `docs/DESIGN.md` 附录 D。

```
%LOCALAPPDATA%\TalkingLive\
├── config.json                           # 配置（启动时强制校验）
├── logs\talkinglive.log                  # 滚动 5MB×3，不记转写内容
└── models\
    └── vosk-model-small-cn-0.22\         # 唤醒/结束词 + 实时预览
```

## 分层（重要）

`core` / `text` 与各引擎接口的算法部分**不依赖 AWT / JNA / 引擎原生库**，
以便在无麦克风、无桌面的环境下跑单元测试。这是本项目测试策略能成立的前提。

```
com.talkinglive
├── App                 装配与启动（唯一有 main 的类）
├── core                StateMachine / Config / DictationSession / JsonCodec / AppState
├── audio               AudioCapture（单路分发 + 重采样）/ SilenceDetector / WavWriter
├── engine              WakeWordDetector / SpeechRecognizer / TextRefiner 接口 + 实现
├── text                CommitPolicy / TextPostProcessor / TextUtils
├── system              TextInjector / ForegroundWatcher / MicValidator / Win32 / DpiScale
└── ui                  FloatingBall / PreviewBar / SettingsWindow / Theme
```
