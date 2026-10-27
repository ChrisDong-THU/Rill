# 后台测试

## 当前验证范围

JVM 测试覆盖 HTTP 字节转发、Range 条件、预检隔离与去重、容量、重试、停止、配置校验和适配。Android 仪器测试覆盖后台心跳、通知时间、资源释放、监听恢复、Wi-Fi 重连和配置文件中断保存。

模拟器结果不代表已验证特定厂商的后台控制，也不等同于真实播放器、Clash VPN 与音频解码输出的联合测试。系统仍可限制网络或暂停进程；Rill 不保证绕过这些限制。

## 复现方法

需要 Python 3.9+、ADB、独立 Android 13+ 模拟器，默认接入点为 `AndroidWifi`。安装 Debug APK并先运行仪器测试：

```powershell
.\gradlew.bat :app:assembleDebug :app:connectedDebugAndroidTest
adb -s emulator-5580 install -r app/build/outputs/apk/debug/app-debug.apk
python tools/background_matrix.py --adb <adb路径> --serial emulator-5580 --output .local/background-matrix/run --duration 120
```

脚本拒绝真实设备。它会覆盖测试应用设置、切换 Wi-Fi 和电量模式、注入网络限制，以及终止或暂停应用进程；请使用独立模拟器。异常退出会尝试恢复系统状态，ADB 故障后应手动核对。结果输出为 `device.json`、`results.json`，不应提交本机诊断与私人配置。

测试以普通界面开启服务后切到系统设置，不依赖 instrumentation 持续保活。上游为本机 HTTP/1.1 服务，使用合成二进制数据，逐请求校验长度及 SHA-256；长流为 32 MiB，超过单任务缓存上限。

| 场景名（可传给 `--only`） | 检查 |
| --- | --- |
| `slow_preflight_isolation` | 慢预检不阻塞另一首歌 |
| `screen_on_background` | 切出界面后持续切歌 |
| `screen_off_long_stream` | 锁屏、慢速读取完整 32 MiB |
| `battery_saver` | 节电模式下持续切歌 |
| `doze_without_exemption` / `doze_with_exemption` | 记录实际 Doze 与网络状态 |
| `doze_exit_recovery` | 退出 Doze 后恢复 |
| `wifi_reconnection` | Wi-Fi 断开后自动恢复 |
| `restricted_network_recovery` | 收到受限回调、解除限制后恢复 |
| `process_suspension_recovery` | 暂停进程 7 秒后继续请求 |
| `process_death_recovery` | START_STICKY 重启与新请求 |
| `post_recovery_background` | 恢复后继续后台切歌 |
| `buffered_playback_idle_resume` | 锁屏空闲 180 秒后重新取流 |

判断报告是否更新时，同时检查 `snapshot_age_ms` 和 `service_tick_age_ms`。快照内的心跳年龄小，不代表快照本身仍然新鲜。

## 历史基线：2026-10-12

环境为独立 Android 15 / API 35 x86_64 模拟器（2 核、2 GiB）。0.3.5 的慢预检曾让另一歌曲超过 5 秒无法返回；0.3.6-rc1 将预检按 URL 隔离，同 URL 合并等待，创建任务计入容量限制。

| 候选版场景 | 记录 |
| --- | --- |
| 慢预检隔离 | 正常歌曲首包 0.203 秒，最终包复测 1.375 秒 |
| 亮屏后台 / 节电模式，各 120 秒 | 各 57 个请求，长度与哈希均一致 |
| 锁屏读取 32 MiB | 83.67 秒，内容一致 |
| Wi-Fi 断开 8 秒后恢复 | 等待请求 14.64 秒完成，无需打开界面 |
| 暂停进程 7 秒 | 独立复测总耗时 8.188 秒，最大心跳间隔 8,064 ms |
| 杀进程后恢复 | 约 2.53 秒出现新进程及健康监听，随后取流成功 |
| 锁屏空闲 180 秒 | 原进程仍在；新请求 800,000 字节，首包 0.594 秒 |
| 正式签名候选覆盖 0.3.5 | 升级成功，亮屏与锁屏各 13 次取流均校验通过 |

非白名单 Doze 已确认进入 IDLE，但该链路仍可联网，只记为观察。受限联网补测收到 `wifi.blocked=true`，随后 ADB 超时使自动恢复和紧接的暂停测试失败；保留这些失败记录。独立解除限制后，未打开 Rill，32 秒内 12 个新 URL 请求通过。单独重跑暂停测试通过。

这些记录没有重现用户手机上「必须切回界面」的完整现象，也不能证明预检锁是该现象的唯一原因。历史记录是基线，不代替当前发布的重新验证。

## 真机问题定位

导出诊断时，同时记录问题时间、手机型号与完整系统版本。结合 `pending_preflights`、`open_clients`、`wifi.blocked`、`service_max_tick_gap_ms`、进程 ID 与事件，可区分请求等待、系统网络限制、进程暂停和重启。

`battery_exempt` 表示 Android 电池优化豁免状态，不代表厂商界面的后台策略。日志保留有限历史，应在恢复后尽快导出。
