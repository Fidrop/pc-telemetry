# Android Dashboard
已有app与telemetry-core模块，最低API29，compile/target35，Java17、Gradle8.11.1、AGP8.9.1、Kotlin2.1.20。
安装SDK35和Build Tools35.0.0，设置JAVA_HOME/ANDROID_HOME，或使用本机.tools专用工具链。根目录执行scripts/build-android.ps1。
Wrapper、依赖锁文件和SHA-256校验元数据已提交，SDK与本地配置不进入Git。
实际APK位于app/build/outputs/apk/debug/app-debug.apk，含SPORT/HUD、配对和设置代码，尚未完成阶段6发布验收。
Android10模拟器完成真实Windows配对、接收与Keystore检查，小米8尚未实测。
当前按用户指令暂停，见docs/work-status.md。
