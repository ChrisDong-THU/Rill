<div align="center">

<img src="docs/assets/rill.svg" width="80" height="80" alt="Rill" />

# 澜流 · Rill

**Android 本地 HTTP Range 中继**

通过 Wi-Fi 并发获取音频分段，按原字节顺序交给播放器。

[![CI](https://github.com/ChrisDong-THU/Rill/actions/workflows/ci.yml/badge.svg)](https://github.com/ChrisDong-THU/Rill/actions/workflows/ci.yml)
[![Release](https://img.shields.io/github/v/release/ChrisDong-THU/Rill)](https://github.com/ChrisDong-THU/Rill/releases/latest)
![Android 8.0+](https://img.shields.io/badge/Android-8.0%2B-245E49)

[下载正式版](https://github.com/ChrisDong-THU/Rill/releases/latest) · [使用指南](docs/usage.md) · [开发说明](docs/development.md) · [更新记录](CHANGELOG.md)

</div>

**已验证场景：使用 BubbleUPnP 软件取流 TIDAL 音源，配合本项目中继已验证有效。**

## 功能

- **并发取流**：1–16 路上游连接，分段缓存、顺序输出与失败重试。
- **Clash 配置适配**：读取文件或订阅，保留原节点与规则，添加本地节点和音频分流。
- **后台会话**：前台通知、Wi-Fi 网络监听、断线与本地监听恢复。
- **运行诊断**：连接、速度、预检、心跳及系统网络状态，可导出 JSON。

仅支持标准 **HTTP Range** 上游；不解码、不转码，不支持 HTTPS 音频取流或额外认证请求头。中继监听 `127.0.0.1:18889`，只使用 Wi-Fi。

## 开始使用

1. 安装 [Release APK](https://github.com/ChrisDong-THU/Rill/releases/latest)，连接 Wi-Fi。
2. 在 Clash 中将「澜流」设为绕过代理，播放器继续由 Clash 接管。
3. 在澜流导入完整 Clash YAML，适配后导入 Clash。
4. Clash 使用规则模式，将「澜流 · 音频」组选为 `Rill Local`，然后在澜流点击「开启」。

停止服务前，将音频组切回 `DIRECT`。详细操作和排障见 [使用指南](docs/usage.md)。

## 构建

需要 **JDK 17**、Android SDK **Platform 34 / Build Tools 34.0.0**；设置 `JAVA_HOME`、`ANDROID_HOME`。项目固定使用 AGP 8.7.3 / Gradle 8.9，由 Wrapper 下载依赖。

```powershell
# Windows
.\gradlew.bat :core:test :app:assembleDebug :app:lintDebug :app:lintRelease checkJavaFormat
```

```sh
# macOS / Linux
./gradlew :core:test :app:assembleDebug :app:lintDebug :app:lintRelease checkJavaFormat
```

Debug APK：`app/build/outputs/apk/debug/app-debug.apk`。正式 APK 使用独立签名，步骤见 [发布指南](docs/releasing.md)。

## 工程

| 目录 | 内容 |
| --- | --- |
| `app/` | Android 界面、前台服务、Wi-Fi、配置存储与分享 |
| `core/` | 纯 Java 中继、HTTP 协议、配置转换与 JVM 测试 |
| `docs/` | 使用、架构、验证与发布指南 |
| `tools/` | 模拟器取流验证、正式包校验与打包 |
| `licenses/` | 打包进 APK 的第三方许可 |

依赖方向为 `app → core → SnakeYAML`。运行与并发约定见 [开发说明](docs/development.md)，设备验证范围见 [后台测试](docs/background-testing.md)。

项目尚未指定开源许可证；第三方组件按 [各自许可](licenses/NOTICE.txt) 分发。
