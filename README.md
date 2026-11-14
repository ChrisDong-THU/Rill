<div align="center">

<img src="docs/assets/rill.svg" width="80" height="80" alt="Rill" />

# 澜流 · Rill

**BubbleUPnP 的 Android 音频 VPN**

通过 Wi-Fi 或蜂窝数据并发获取音频分段，按原字节顺序交给播放器。

[![CI](https://github.com/ChrisDong-THU/Rill/actions/workflows/ci.yml/badge.svg)](https://github.com/ChrisDong-THU/Rill/actions/workflows/ci.yml)
[![Release](https://img.shields.io/github/v/release/ChrisDong-THU/Rill)](https://github.com/ChrisDong-THU/Rill/releases/latest)
![Android 10+](https://img.shields.io/badge/Android-10%2B-245E49)

[下载正式版](https://github.com/ChrisDong-THU/Rill/releases/latest) · [使用指南](docs/usage.md) · [开发说明](docs/development.md) · [更新记录](CHANGELOG.md)

</div>

## 功能

- **独立音频 VPN**：通过 Android VPN 接管 BubbleUPnP 发往已配置音源 IP 的连接。
- **并发取流**：匹配目标域名和端口的 HTTP 请求按 1–16 路分段下载，并按字节原序交付给播放器。
- **后台会话**：使用前台 VPN 服务，随系统切换 Wi-Fi 或蜂窝数据，通过通知和诊断查看运行状态。

## 开始使用

1. 安装澜流和 BubbleUPnP。已有其他 Android VPN 正在运行时，请先停止它。
2. 在澜流「设置 → 加速链接」填写 HTTP 音源域名或地址。默认目标为 `lgf.audio.tidal.com`。
3. 在澜流点击「开启」，首次使用时确认系统 VPN 授权，然后在 BubbleUPnP 开始播放。

澜流分段加速支持 HTTP Range 的音源。更多设置和排障见 [使用指南](docs/usage.md)。

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
| `app/` | Android 界面、分流 VPN、上游网络与诊断 |
| `core/` | 纯 Java 中继、HTTP 协议与 JVM 测试 |
| `docs/` | 使用、架构、验证与发布指南 |
| `tools/` | 模拟器取流验证、正式包校验与打包 |
| `licenses/` | 打包进 APK 的第三方许可 |

Android 端使用 hev-socks5-tunnel，HTTP Range 中继位于 `core`。运行与并发约定见 [开发说明](docs/development.md)，设备验证范围见 [后台测试](docs/background-testing.md)。

项目尚未指定开源许可证；第三方组件按 [各自许可](licenses/NOTICE.txt) 分发。
