# 发布指南

## 版本与签名

版本来源为根目录 `gradle.properties`。正式版使用 `x.y.z`，每次发布都增加 `rillVersionCode`，并更新 [CHANGELOG](../CHANGELOG.md)。Git 标签为 `v<版本>`。

正式包使用独立发布签名。证书 SHA-256：

```text
5f3d35c836f1b7c2c5bc14a6e7ecac6b9ce74fb6c83d2427998012d822b57840
```

复制 `keystore.properties.example` 为 `keystore.properties`，填写已有密钥信息；路径相对项目根目录。也可使用环境变量：

| 配置键 | 环境变量 |
| --- | --- |
| `storeFile` | `RILL_KEYSTORE_PATH` |
| `storePassword` | `RILL_KEYSTORE_PASSWORD` |
| `keyAlias` | `RILL_KEY_ALIAS` |
| `keyPassword` | `RILL_KEY_PASSWORD` |

密钥和密码应安全备份，不能加入源码、CI 产物或 Release 附件。缺少完整签名配置时，Release 打包会失败。

## 验证和打包

1. 完成代码审查、版本与文档更新。
2. 使用 JDK 17 运行：

   ```powershell
   .\gradlew.bat :core:test :app:assembleDebug :app:assembleRelease :app:lintDebug :app:lintRelease checkJavaFormat
   .\gradlew.bat :app:connectedDebugAndroidTest
   ```

3. 对涉及取流的改动执行 [后台测试](background-testing.md)，记录环境、结果和未覆盖范围。正式包另验证安装、后台取流和启停。
4. 提交已验证的源码，重新构建正式包，使包内版本控制信息对应发布提交：

   ```powershell
   git status --short
   .\gradlew.bat :app:assembleRelease
   python tools/package_release.py --sdk <Android-SDK目录>
   ```

脚本检查签名、证书延续、ZIP 对齐、包名、版本和非调试标记，输出到 `app/build/outputs/releases/<版本>/`：

| 附件 | 内容 |
| --- | --- |
| `Rill-<版本>.apk` | 正式签名安装包 |
| `SHA256SUMS.txt` | APK 的 SHA-256 |
| `verification.json` | 签名指纹、版本、源码提交和校验记录 |

同版本目录已有不同 APK 时脚本拒绝覆盖。重新构建应使用新版本，或将尚未发布的旧验证产物保存在其他本机目录后重试。

## GitHub 发布

先推送提交并确认 CI 通过，再为同一提交创建带说明的标签并推送。使用 GitHub CLI 创建 Release，先上传并核对附件，最后发布：

```powershell
git push origin main
git tag -a v<版本> -m "Rill <版本>"
git push origin v<版本>
gh release create v<版本> --verify-tag --draft --title "澜流 Rill <版本>" --notes-file <发布说明文件> <APK> <SHA256SUMS.txt> <verification.json>
gh release view v<版本>
gh release edit v<版本> --draft=false --latest
```

发布说明应包括用户可见改动、已验证使用场景、测试结果及实际测试边界。正式版不勾选 prerelease。发布后确认 Release 指向预期提交，下载附件并核对 SHA-256。
