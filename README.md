# MT论坛

版本：1.0.0（versionCode 1）。Android 原生 Java 的论坛客户端，基于所提供的「论坛源码_v2.2」作二次开发。原包的说明保存于 [docs/ORIGINAL_README.md](docs/ORIGINAL_README.md)，现有源码中的作者和版权信息保留。

> 原包没有提供许可证或明确的公开再分发授权。在确认授权之前，请保持仓库私有，不要公开源码或发布 Release。请向原作者确认权利、署名要求和许可文本。

## 功能和导航

底部导航按原有顺序保留：首页、版块、发布、消息、我的。保留原有论坛解析、帖子阅读/发布/回复、账号、AI 聊天及侧边栏自动回复和自动解锁逻辑。界面调整仍需在真实设备及论坛账户上验收。

## 构建

JDK 17、Android SDK API 36、Gradle Wrapper 8.13、Android Gradle Plugin 8.13.0。运行：

```bash
bash gradlew clean test assembleDebug lint
```

调试 APK 在 `app/build/outputs/apk/debug/app-debug.apk`。GitHub Actions 会复制为 `MT论坛-1.0.0.apk` 并作为同名 Artifact 保存。该包使用 Android 调试签名，仅供测试，不是正式发布包。没有配置正式签名；原包所附签名文件和密码已移除。

包名仍为 `com.solosu.mtforum`，与原版同时安装可能冲突。由于 versionCode 重置为 1，无法保证覆盖已有 2.2 版本；安装前先备份账户资料和草稿。改变包名涉及账户持久化、第三方回调和更新链路，需单独验证后再决定。

## 验证状态

GitHub Actions [第 6 次运行](https://github.com/STARSHINE56/MTForum/actions/runs/36650906918) 已完成 `clean test assembleDebug lint` 并上传测试 APK。`test` 任务没有现成的单元测试源码；lint 没有错误，仍有原项目警告。APK 文件 SHA-256：`c440448ada1d8c43c0242f34ec141bf9bd522da39e9a647b905cdf159e7e616b`。

尚未进行真机启动、底部导航逐项、登录、真实论坛发帖与回复、AI 服务和自动回复的端到端验收。原始授权未明确，且该 APK 使用 Debug 签名，因此暂不创建 Release 或宣称正式版。
