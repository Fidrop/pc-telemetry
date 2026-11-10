# 跨设备接续
公开仓库：https://github.com/Fidrop/pc-telemetry 。main为工作分支，匿名克隆无需登录，推送需要写权限。

```powershell
git clone https://github.com/Fidrop/pc-telemetry.git
cd pc-telemetry
```

用户已于2026-11-10明确恢复开发，并授权连续推进、逐阶段记录、每批验证后推送。先读docs/work-status.md、完整需求docs/requirements/source-message.md、docs/acceptance.md和最新阶段报告，并查看design_ref两张原图。不要根据历史阶段报告的暂停字样再次暂停。

开发需要.NET SDK 8.0.427、JDK17、Android SDK35与Gradle8.11.1（含wrapper）。执行scripts/build-windows.ps1 -Test和scripts/build-android.ps1。项目内.tools为原机器本地工具，Git和源码包不包含；新机器应自行安装。运行时凭据使用Windows DPAPI或Android Keystore，不随源码迁移，换机重新配对。

交付包与校验文件在artifacts，构建/打包脚本在scripts。完整需求、锁文件、设计图、测试记录随源码保存。换机前提交推送，开始前拉取最新main。

当前限制见阶段报告：本机缺失温度与专用VRAM保持N/A，工作集超80MB；实体手机、真实局域网mDNS、跨GPU、游戏影响和4小时稳定性未完成。不得将模拟器联调等同于实体设备验收。
