# MTForum — MT 论坛第三方客户端

[bbs.binmt.cc](https://bbs.binmt.cc/) 的第三方 Android 客户端。原生 Java + Material Design，覆盖板块浏览、帖子阅读、回复/发帖、个人中心、多账号、AI 自动签到/自动回复。

## 构建

需要：JDK 17、Android SDK 36、Gradle 9.0+

```bash
# 首次构建会自动下载 gradle wrapper 和依赖
gradle :app:assembleDebug        # 调试包
gradle :app:assembleRelease      # 发布包(需 keystore)
```

> aapt2 走 Android SDK 自带即可。项目 `gradle.properties` 已剔除本机 `aapt2FromMavenOverride` 绝对路径，别加回来。

### 发布包签名

`app/keystore.jks` 为打包示例（密码 `mtforum123`、别名 `mtforum`、有效期 10000 天）。**分享出去前请重新生成自己的 keystore，别用这个**：

```bash
keytool -genkeypair -v -keystore your.jks -alias your_alias -keyalg RSA -keysize 2048 -validity 10000
```

然后修改 `app/build.gradle` 里的 `signingConfigs.release` 三个字段。

## 目录结构

```
MTForum_source/
├── build.gradle                 # 根配置
├── settings.gradle
├── gradle.properties
├── gradle/
│   ├── libs.versions.toml       # 依赖版本
│   └── wrapper/
│       └── gradle-wrapper.properties
├── app/
│   ├── build.gradle             # app 配置(含 signingConfigs)
│   ├── proguard-rules.pro
│   ├── keystore.jks             # 示例签名,请自换
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/                # 源代码
│       └── res/                 # 布局/资源
└── README.md
```

## 依赖

OkHttp（网络）、Jsoup（HTML 解析）、Glide（图片）、Material Components（UI）、ViewPager2、DrawerLayout、WebKit。

## 注意

- 代码为 build81 版本(v2.2)，包含登录链路修复、全标签富文本重写、举报弹窗（Chip 单选理由）、评论菜单卡片化、列表页去收藏格、崩溃日志镜像、点赞数同步、回复发送后清空图片、消息红点本地即时清零等改动
- `HttpClient` 里的 USER_AGENT 是写死的（三星 S918B/Chrome 120），论坛风控严格时可按需调整
- 论坛站点 `bbs.binmt.cc` 挂了阿里云 ESA，频率过高会被 IP 级 403 拦截，别短时间内连发请求
