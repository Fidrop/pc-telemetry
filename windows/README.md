# Windows Collector
需要Windows10/11 x64与.NET8 SDK。当前默认无参数启动托盘，可打开限时配对窗口；带参数的控制台调试入口继续可用。最新状态见docs/stage-5-report.md及docs/stage-5-resumed-report.md。

## 构建和检查
在根目录执行scripts/build-windows.ps1 -Test。依赖已锁定，使用--locked-mode还原。
LibreHardwareMonitorLib0.9.6采用MPL-2.0，ProtectedData8.0.0及传递依赖随锁文件固定。随包提供许可证与通知。0.9.6不再携带WinRing0；本项目不安装PawnIO，缺失传感器显示N/A。

## 运行
构建后在PowerShell执行
```powershell
$collector = "./windows/Collector/bin/Release/net8.0-windows/Collector.exe"
& $collector --data-dir "./runtime/manual"
```
另一个窗口用同一账户连接
```powershell
& "./windows/Collector/bin/Release/net8.0-windows/Collector.exe" --probe 8 --data-dir "./runtime/manual"
```
如使用项目专用SDK，先将DOTNET_ROOT指向.tools/dotnet。全局环境需要.NET8 Windows Desktop与ASP.NET Core运行时，安装SDK会同时提供它们。
Ctrl+C退出，--samples N限制采样次数。--output FILE显式输出快照JSONL，--diagnostics FILE显式记录分组更新耗时与重启次数，文件父目录须已存在。
--port修改47631默认端口。--gpu ID启动时指定GPU，稳定ID来自认证后hello的gpuList；运行中支持gpu.select。选择保存到data-dir/gpu.json。

## 安全与资源
控制台默认WSS仅绑定IPv4回环；--lan或默认托盘入口另绑定私有/域网络IPv4地址。开发探针从同一data-dir读取DPAPI测试凭据，固定TLS证书SHA-256。证书私钥与token保存在secrets/identity.dpapi，secrets子目录仅授予当前账户权限。
证书使用Windows默认密钥集供Schannel加载，未启用PersistKeySet，释放时回收临时私钥；不会加入系统信任根。测试凭据不用于正式配对。
旧开发目录根部的identity.dpapi首次打开时迁移到secrets子目录。不同Windows账户无法解密，源码迁移排除runtime。
worker使用当前用户专用命名管道和Job Object，父进程意外退出时回收子进程。硬件调用超时杀死worker，隔离对应组60秒后恢复探测。普通权限优先，不自动弹出UAC。

## 已知限制
本机温度与专用VRAM不可用，管理员权限回退尚未实测。AMD/NVIDIA、多GPU实机、Windows10和长期稳定性仍待验证。
短测工作集超过80MB目标，详见阶段1报告。当前构建未通过最终性能验收。
