# MT论坛

版本：1.0.0（versionCode 1）。Android 原生 Java 的论坛客户端，基于所提供的「论坛源码_v2.2」作二次开发。原包的说明保存于 [docs/ORIGINAL_README.md](docs/ORIGINAL_README.md)，现有源码中的作者和版权信息保留。

> 原包没有提供许可证或明确的公开再分发授权。在确认授权之前，请保持仓库私有，不要公开源码或发布 Release。请向原作者确认权利、署名要求和许可文本。

## 功能和导航

底部导航按原有顺序保留：首页、版块、发布、消息、我的。保留原有论坛解析、帖子阅读/发布/回复、账号、AI 聊天及侧边栏自动回复和自动解锁逻辑。界面调整仍需在真实设备及论坛账户上验收。

## 构建

JDK 17、Android SDK API 36、Gradle Wrapper 8.13、Android Gradle Plugin 8.13.0。运行：

```bash
./gradlew clean test lint assembleDebug
```

调试 APK 在 `app/build/outputs/apk/debug/app-debug.apk`。GitHub Actions 会复制为 `MT论坛-1.0.0.apk` 并作为同名 Artifact 保存。该包使用 Android 调试签名，仅供测试，不是正式发布包。没有配置正式签名；原包所附签名文件和密码已移除。

包名仍为 `com.solosu.mtforum`，与原版同时安装可能冲突。由于 versionCode 重置为 1，无法保证覆盖已有 2.2 版本；安装前先备份账户资料和草稿。改变包名涉及账户持久化、第三方回调和更新链路，需单独验证后再决定。

## 验证状态

本地没有 Android SDK 和可下载的 Gradle 分发环境，尚未跑通 Android 构建或真机验收；应以实际 Actions 运行结果为准。未经验证不要宣称正式发布成功。
