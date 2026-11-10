# 跨设备接续
阶段1已实现Windows真实采集和本机WSS，源码压缩包已更新。下一阶段为Android数据接收与状态管理，开始前仍需用户确认。

## 迁移
将最新PC-Telemetry-stage1 ZIP解压到独立目录，用另一台电脑的Codex打开。
先读README.md、docs/requirements/source-message.md、docs/stage-1-report.md、docs/architecture.md、docs/acceptance.md、docs/visual-design.md与design_ref/README.md，并查看两张PNG。
包中包括源码、锁文件、脚本、设计图与实测记录；排除.tools、runtime、bin/obj及artifacts，开发凭据和SDK不随包迁移。docs/environment.json仅记录原电脑。
目标电脑安装.NET8 SDK，执行scripts/build-windows.ps1 -Test和scripts/verify-live-windows.ps1。Android阶段需检查JDK17、Android SDK和Gradle。
聊天历史不会随文件迁移，完整需求与当前限制均已写入项目。

## 当前限制
Windows阶段1只监听127.0.0.1。发现、正式配对、托盘和自启归阶段5；阶段2双端实机联调前落实固定证书与受控测试凭据。
原电脑CPU/GPU使用率和RAM可用，温度与专用VRAM不可用。短测工作集超过80MB，后续需优化。

## GitHub
已通过GitHub官方CLI创建私有仓库 https://github.com/Fidrop/pc-telemetry ，当前分支main，阶段1源码、两张设计图与测试记录已推送。
原电脑项目专用CLI位于.tools/github-cli/bin/gh.exe，通过官方发布文件校验SHA-256后安装，认证保存在Windows凭据存储中。CLI、凭据与SDK均不进入仓库或源码包。
另一台电脑先登录有该私有仓库权限的GitHub账户，然后执行
```powershell
git clone https://github.com/Fidrop/pc-telemetry.git
cd pc-telemetry
```
在Codex中打开克隆目录，按上述阅读顺序检查需求与阶段状态。换设备前提交并推送，开始工作前先拉取最新main。
本次Git提交使用Fidrop及GitHub noreply邮箱，配置仅限本项目。

## 阶段门禁
阶段1完成后停止等待用户确认。明确确认阶段1并开始阶段2后，再开发Android。每阶段确认来自最初需求，迁移文件不触发后续阶段。
