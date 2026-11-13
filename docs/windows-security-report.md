# Windows 驱动安全与管理员启动修复

2026-11-13。以下为 Preview 9 的安全修复记录。Preview 10 新增经用户确认的 PawnIO 安装引导，见 driver-setup-report.md。

## 原因与处理

Defender 的具体检测名是 `VulnerableDriver:WinNT/Winring0`，旧版 LibreHardwareMonitorLib 0.9.4 会释放为 `Collector.sys`。这是易受攻击驱动检测，不能简单归为误报。本次升级至稳定版 0.9.6（官方源码提交 `3d331e3370efb858411f19511373eff65a218701`），不修改第三方库。核对该版本的 Computer.Open 和 PawnIo.LoadModuleFromResource：不再有旧 Ring0 加载器，PawnIO 只打开已存在的设备，本项目不安装其驱动。

正常双击和 `--tray` 启动会先检查管理员令牌，普通权限时使用 Windows 标准 runas 授权重启同一个程序，参数按独立参数传递，工作目录固定为应用目录。管理员实例不会重复请求；取消授权不重试。采集子进程继承管理员令牌。诊断 CLI 保留当前权限，不要求提权。注册表自启入口仍先启动普通进程，再请求授权；尚未完整验证注销/重新登录。

## 当前验证

- Windows 编译零警告、零错误；70 项检查通过，新增无 Ring0 加载器及无 .sys/WinRing0 内嵌资源的检查。
- 全新独立输出目录发布，目录中没有 .sys 文件，未混入旧包。
- 从普通权限启动新版托盘并完成 Windows 授权后，使用 OpenProcessToken/GetTokenInformation(TokenElevation) 实际读取：托盘与采集子进程均为管理员令牌。
- 两次真实采集（20/15帧），CPU/GPU 使用率与 RAM 有真实值；本机未安装 PawnIO，温度和专用显存仍为 N/A。
- Defender 实时防护与杀毒均保持开启；签名版本 1.463.275.0。对新版完整目录完成自定义扫描，无本次新增检测。未关闭防护、添加排除或恢复旧驱动。
- 旧 R0Collector 服务登记仍为停止状态，没有重新加载或删除其隔离记录。
- preview7/preview8 GitHub 发布说明已补安全告知。

这只证明当前机器、当前签名库和这次测试的结果，不保证未来所有杀毒产品均不告警；软件仍未代码签名。请全新解压新版，不要覆盖旧文件。管理员权限不能补齐未提供的传感器。
