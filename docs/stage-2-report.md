# 阶段2报告
2026-11-10。用户已确认连续推进后续阶段，逐阶段记录并推送GitHub。

## 实现与验证
Android app采用API29最低版本、compile/target35、Java17、Kotlin2.1.20、Compose与独立telemetry-core模块。
实现WSS证书指纹固定、认证、严格JSON解析、设备/会话/序列校验、单调时钟5秒离线、数据年龄与最后读数保留、单连接退避重连、固定60点历史、缺失值N/A及独立DEMO。凭据采用Android Keystore AES-GCM加密后保存至DataStore，关闭系统备份。
基础界面为原生Compose，横屏沉浸式、前台常亮，后台关闭连接与定时更新。正式SPORT/HUD由阶段3/4实现。

## 实际结果
- 16项协议/状态/缓存/重连/DEMO单元测试通过。
- 3项真实Windows联调通过，包括Android共享JVM代码接收8条真实快照、错误证书pin拒绝、错误token拒绝。开发凭据通过临时进程环境传递，未写入明文配置或测试报告。
- app:assembleDebug与lintDebug成功，首个APK实际生成。Lint为0错误、21警告，主要为依赖存在新版本及版本目录使用建议。
- 依赖锁文件与SHA-256校验元数据已生成，官方Gradle Wrapper已生成。

## 工具环境与限制
现有D:/JDK为Java26，与选定构建链不匹配，项目.tools内安装Temurin17.0.21并校验官方SHA-256。Gradle8.11.1亦校验SHA-256。Android SDK35、Build Tools35.0.0与Platform Tools通过sdkmanager安装，执行了SDK许可证接受步骤，均为本机开发工具，不进入仓库。
ADB设备列表为空，未在小米8或其他Android设备安装运行。共享JVM联调不等于手机显示与触控已验证。Android Keystore/DataStore实际行为、刘海、常亮、后台恢复与设备帧率仍待实机。
Windows仍只绑定127.0.0.1。手机开发联调可用adb reverse tcp:47631 tcp:47631，正式局域网配对由阶段5完成。当前开发连接需手动输入受控凭据，不能直接面向普通用户发行。
正式APK交付与统一报告将在阶段6生成，后续主题/设置变化将继续重建APK。
