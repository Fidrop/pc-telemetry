# PC Telemetry 1.0.0 正式发布记录

2026-11-17。用户授权将已审阅的codex/ui-polish分支合并到main，并以v1.0.0正式GitHub Release分发。

## 本次内容

整合SPORT金属仪表与指针/读数层级、HUD趋势图、设备/显示/声音分页设置，保留原异常反馈。PC端保留Preview10的自动管理员授权、PawnIO确认安装引导与WinRing0依赖修复，本次统一正式版本信息，不新增PC功能。

- Android：1.0.0，versionCode12，Release构建，非debuggable，不启用代码压缩。
- APK签名沿用既有证书，支持覆盖Preview9/11并保留设置和配对。该证书仍为原开发证书，面向应用商店的生产签名迁移未包含在本次发布中。
- Windows：产品版本1.0.0，文件版本1.0.12.0，自包含win-x64；仍未代码签名。驱动仅在用户确认后从官方地址下载，不改变Defender设置。
- 运行ZIP、独立APK、源码ZIP均附SHA-256；运行包记录main的精确合并提交，附界面截图、依赖通知及验证记录。

## 发布构建验证

| 检查 | 结果 |
| --- | --- |
| Windows编译与测试 | 零错误、零警告，97项通过 |
| Android核心 | 19项通过；3项独立凭据JVM联调按设计跳过 |
| Release构建与Lint | 构建通过，Lint 0错误、20条警告 |
| APK与升级 | 签名有效，与Preview11一致；调试标记关闭，覆盖安装成功 |
| 实际Release APK界面/引擎 | 6项通过：设置分页/保存、主题、语言、时间电量、引擎保持启动与扫表隔离 |
| 实际Release APK麦克风 | 3项通过：原生拒绝/授权、生命周期释放、灵敏度保存与信号映射 |
| 实际Release APK真实Windows联调 | 1项通过：固定证书、配对、同凭据断开重连、远端撤销 |
| 实际Release APK加密存储 | 2项通过：旧档迁移、多设备凭据保存与切换 |
| Windows候选发布目录扫描 | Defender实时防护开启，无新增检测 |

真实联调使用独立47635端口，测试不占用用户当前PC端的47631。模拟器未启用宿主音频输入，音频检查包括原生权限、实际录音会话与固定PCM信号，不代表实体手机拾音效果验收。

本次版本准备仅改变构建、版本与交付信息，界面行为沿用已审阅实现。[原生界面截图与测量](ui-polish-report.md)中的性能数据来自Preview11：软件模拟器SPORT/HUD p95约28/30ms，尚未达到60fps预算；本次Release不宣称已完成性能验收。

## 复现

在PowerShell7、仓库根目录执行：

```powershell
./scripts/build-windows.ps1 -Test
./scripts/publish-windows.ps1 -Destination artifacts/PC-Telemetry-Windows-1.0.0
./scripts/build-android.ps1 -Configuration Release
./scripts/package-deliverables.ps1 -WindowsDirectory artifacts/PC-Telemetry-Windows-1.0.0 -AndroidConfiguration Release
./scripts/package-project.ps1 -Stage stage6
```

SDK、缓存、凭据和签名私钥不包含在源码包中。更换签名证书会影响覆盖升级，请保留发布设备上的现有签名资料。测试脚本仍默认Debug，可显式选择Release。

## 继续保留的限制

实体手机与更多CPU/GPU兼容性、跨设备mDNS、登录自启、睡眠恢复、游戏影响和长时间运行尚未完整验证；Windows工作集目标仍未达标。CPU温度依赖PawnIO和硬件支持，部分GPU温度/专用VRAM仍为N/A。详见[已知问题](known-issues.md)。正式GitHub Release是本次分发类型，不表示上述验收缺口已经消失。
