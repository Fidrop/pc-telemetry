# PC Telemetry v1.0
Windows Collector + Android Dashboard。已实现Android接收、SPORT/HUD及主要配对/设置代码，并完成Android10模拟器真实联调。已交付阶段6预览构建，当前进度见[工作状态](docs/work-status.md)。

项目公开仓库 https://github.com/Fidrop/pc-telemetry ，可匿名访问及克隆main分支，接续说明见docs/handoff.md。

## 项目资料
- [完整权威需求](docs/requirements/source-message.md)
- [阶段0报告](docs/stage-0-report.md)
- [阶段1报告](docs/stage-1-report.md)
- [阶段3记录](docs/stage-3-report.md)
- [阶段4记录](docs/stage-4-report.md)
- [阶段5补充验证](docs/stage-5-resumed-report.md)
- [阶段6构建与性能报告](docs/stage-6-report.md)
- [安装、配对与故障排查](docs/user-guide.md)
- [已知问题](docs/known-issues.md)
- [ROG / HUD / 设置视觉更新与截图](docs/visual-revision-report.md)
- [时间、电量与SPORT标志调整](docs/clock-battery-report.md)
- [SPORT顶部电量灯条](docs/sport-battery-bar-report.md)
- [依赖与工程方案](docs/architecture.md)
- [协议草案](protocol/README.md)
- [安全方案](docs/security.md)
- [视觉实现](docs/visual-design.md)
- [阶段与验收](docs/acceptance.md)
- [实测计划](tests/README.md)
- [设计图核对](design_ref/README.md)
- [另一台设备接续开发](docs/handoff.md)
- [Windows构建和运行](windows/README.md)

## 构建与检查
Windows x64，安装.NET8 SDK（global.json约束8.0.4xx功能带）后，在PowerShell7执行
```powershell
./scripts/build-windows.ps1 -Test
./scripts/check-stage0.ps1
./scripts/check-protocol-schema.ps1
./scripts/verify-live-windows.ps1
./scripts/package-project.ps1 -Stage stage6
```
构建脚本也支持本项目.tools/dotnet内的独立SDK，SDK与缓存不随源码包迁移。
当前Windows无参数启动进入托盘，控制台调试默认回环，--lan允许绑定私有网络地址。Android已有Debug构建与配对代码，自包含便携发布已构建，实机与性能仍有未达标/未验证项。
源码压缩包不包含SDK、凭据和构建输出。预览交付包包含自包含便携EXE与Debug APK，生成方法见阶段6报告。

预览构建：scripts/publish-windows.ps1、scripts/build-android.ps1；运行scripts/package-deliverables.ps1生成带许可与校验的交付ZIP。工作集与模拟器帧耗时未达最终目标，请阅读已知问题。
