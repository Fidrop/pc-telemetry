# PC Telemetry v1.0
Windows Collector + Android Dashboard。阶段1已实现Windows真实采集与本机加密通信，等待用户确认进入阶段2。

## 项目资料
- [完整权威需求](docs/requirements/source-message.md)
- [阶段0报告](docs/stage-0-report.md)
- [阶段1报告](docs/stage-1-report.md)
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
./scripts/package-project.ps1 -Stage stage1
```
构建脚本也支持本项目.tools/dotnet内的独立SDK，SDK与缓存不随源码包迁移。
阶段1为控制台开发入口，WSS仅监听127.0.0.1，使用本账户的加密开发凭据。Android和正式配对流程尚待后续阶段。
源码压缩包不包含SDK、凭据和构建输出。阶段6再交付自包含便携EXE与APK。
