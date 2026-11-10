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
连接身份Fidrop，连接器未返回可访问仓库，未提供创建仓库入口，本机未找到gh。本次继续ZIP快照，没有创建或推送远程仓库。
持续跨设备开发可使用用户提供的私有仓库URL，初始化Git后提交推送。换设备前提交推送，开始工作前拉取。

## 阶段门禁
阶段1完成后停止等待用户确认。明确确认阶段1并开始阶段2后，再开发Android。每阶段确认来自最初需求，迁移文件不触发后续阶段。
