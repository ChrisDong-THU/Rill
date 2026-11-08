# 开发说明

## 架构

```mermaid
flowchart LR
    UI[MainActivity / Design] --> Files[ConfigFiles]
    UI --> Import[ClashImporter / ConfigProvider]
    UI --> Service[RelayService]
    Service --> Network[WifiUpstream / BackgroundGuard]
    Service --> Relay[RelayCore]
    Relay --> HTTP[RelayHttp]
    UI --> Config[ConfigSource / ConfigAdapter / Targets]
    Relay --> Targets[Targets]
```

| 层 | 职责与边界 |
| --- | --- |
| Android 界面 | `MainActivity` 组织连接、配置和记录页面；`Design` 统一样式 |
| Android 配置 | `ConfigFiles` 管理私有配置文件的原子写入；`ClashImporter` 创建不可变快照；`ConfigProvider` 仅提供授权读取 |
| Android 会话 | `RelayService` 管理前台会话、监听恢复、采样与异步诊断；`WifiUpstream` 注入 Wi-Fi 连接；`BackgroundGuard` 管理取流唤醒锁 |
| Java 中继 | `RelayCore` 管理连接配额、任务、分块、缓存和重试；`RelayHttp` 管理有界 HTTP 解析与单段 Range |
| Java 配置 | `Targets` 校验允许来源；`ConfigSource` 有界读取和下载；`ConfigAdapter` 安全解析及生成 YAML |

`core` 不引用 Android API。网络通过 `RelayCore.Opener` 注入，JVM 测试使用本地合成上游。UI 保持原生 Java View，读取进程内服务状态；更换绑定或状态模型需要单独验证生命周期。

## 并发与资源

- 任务缓存由 `jobs` 锁保护，网络预检在锁外执行。同 URL 合并预检，不同 URL 可并行；创建中的任务计入最多两个任务的容量。
- 每个任务最多缓存 48 个分块，每块最多 256 KiB。两个任务的音频缓存上限合计为 24 MiB。每个读者从最多 3 块开始预取，以覆盖非对齐请求的 512 KiB 启动缓冲。连续发送的字节达到整块时才扩张窗口，直到连接上限对应的窗口大小。优先安排各读者最近的分块。排队时只保留分块信息，开始下载才分配字节数组，末块只取到请求所需的字节。读者离开后清理失效的预取，待在途读取结束后，每个空闲任务最多保留最近 4 块（1 MiB）。
- 全局上游配额为 1–16，包含预检。降低配额后允许已有连接完成，重试退避期间归还连接配额，空闲取流线程超时退出。
- 临时预检和分块失败最多尝试 3 次；协议、内容范围或版本校验失败直接拒绝。
- `If-Range` 条件在 Range 解析前判断；过期或弱 ETag 条件返回完整内容。HEAD 忽略 Range。语义依据 [RFC 9110](https://www.rfc-editor.org/rfc/rfc9110.html#name-if-range)。
- 服务维护、网络回调、诊断写盘分别使用独立线程；停止时关闭监听、客户端、上游、任务及线程。
- 诊断有活动时每 10 秒采样，空闲时每 60 秒采样。后台调度恢复后即时采样。周期写盘只保存最近 24 条采样和 60 条事件，手动导出和停止服务时保存完整的有界历史（最多 900 条采样和 200 条事件）。诊断中的 `cached_audio_bytes` 记录当时已分配的音频缓存。
- 前台会话与监听就绪分别表示。监听失败保留开启意图，由服务自动恢复，不依赖 Activity 的 `onResume`。
- 已通过目标校验的 GET 和 HEAD 在预检前获取唤醒锁。最后一个请求结束后保留 60 秒宽限期，之后释放 CPU 锁及旧版 Wi-Fi 锁。前台监听和 Wi-Fi 网络请求由会话管理，停止服务时统一释放。Android 14 起不再使用旧式 Wi-Fi 高性能锁。

Media3 与 mpv 的播放器缓存可以用解码时间和缓存字节数决定何时继续加载。Rill 能观察到的是本地套接字成功发送的字节，所以使用读取位置推进窗口，并保留字节上限。rclone 也把分块尺寸和并行读流作为独立参数。联合测试中，BubbleUPnP 在开播后很快接收了大部分音频，4 路和 8 路的 CPU 采样接近。单次短样本不足以据此调低默认连接上限。 [Media3 缓冲策略](https://developer.android.com/reference/androidx/media3/exoplayer/DefaultLoadControl) · [mpv 缓存选项](https://github.com/mpv-player/mpv/blob/master/DOCS/man/options.rst) · [rclone 分块读取](https://rclone.org/commands/rclone_mount/#vfs-chunked-reading)

配置读取、适配和导出在 I/O 线程完成，失败时保留上次可用结果。界面只保留配置的可用状态和来源，导出及分享时分段复制已适配的文件。`ConfigFiles` 对源配置和适配结果分别使用 `AtomicFile`，每次中断写入只回滚对应文件。

## 运行约定

包名为 `app.rill.android`，设置存于 `rill` SharedPreferences。配置路径为 `source.yaml`、`adapted.yaml`、`shared/`，诊断为 `relay-result.json`。

最低 API 26，compileSdk / targetSdk 34。版本只在根目录 `gradle.properties` 修改，诊断使用 `BuildConfig.VERSION_NAME`；健康检查返回稳定的 `Rill ready`。

## 本地检查

环境与完整构建命令见 [README](../README.md)。

```powershell
.\gradlew.bat :core:test       # JVM 回归与本地 HTTP 集成测试
.\gradlew.bat formatJava       # Google Java Format
.\gradlew.bat checkJavaFormat  # 只检查格式
.\gradlew.bat :app:connectedDebugAndroidTest
```

设备测试应在独立 Android 13+ 模拟器上运行。服务测试会修改测试应用设置、启停服务及切换 `AndroidWifi`；真实设备自动跳过服务测试。配置文件测试只使用测试专用的临时缓存子目录。Debug 与正式包签名不同，切换前需在独立模拟器中移除旧测试安装。

CI 对提交、PR 和版本标签运行 JVM 测试、Debug 构建、Debug / Release Lint 和格式检查，保留报告及 Debug APK 14 天。正式签名仅在发布机器配置，不上传到 CI。

改动取流行为应覆盖 Range、字节完整性、上游失败、重试和关闭；改动配置行为应覆盖重复适配、名称冲突、非法输入与大小上限。涉及 Android 生命周期的改动还应运行 [后台测试](background-testing.md)。

## 维护约定

只维护 `app/src`、`core/src` 中的现行源码；历史版本交给 Git 与 Releases。APK、缓存、SDK 路径、密钥和个人订阅均不提交。第三方许可统一存放于 `licenses/`，构建时打包进 assets。

依赖固定为 SnakeYAML 2.4、JUnit 4.13.2、Google Java Format 1.24.0；工具链固定为 AGP 8.7.3 / Gradle 8.9 / JDK 17。升级工具链和 targetSdk 时应独立验证行为变化。
