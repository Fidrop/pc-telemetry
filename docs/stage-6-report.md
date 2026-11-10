# 阶段6：构建、测量与预览交付
2026-11-10。已完成当前环境可执行的构建、自动化验证及预览交付准备；尚未达到全部性能与实机验收条件。

## 实际构建与验证
- Windows SDK8.0.427 / runtime8.0.33，Release编译零警告零错误，68项检查通过；阶段5补充报告包含配对/撤销、隔离自启配置、私有网络匹配与worker恢复证据。
- 实际执行win-x64 self-contained发布，输出完整便携目录，使用独立发布锁文件，未裁剪或强行合并依赖。普通用户无需另外安装.NET。
- 实际运行发布目录中的Collector.exe，LAN托盘模式采样120次；固定TLS证书、认证WSS探针收到8次真实快照，两进程正常退出，Collector/Probe退出码均0，worker重启0次。120个快照全部通过协议Schema。
- Android1.0-preview（versionCode2）、最低API29的Debug APK实际构建、安装成功；核心18项测试通过，普通运行跳过3项需显式凭据的JVM联调。另有模拟器真实配对、遥测、Keystore与服务端撤销检查通过。
- 最终模拟器两主题切换/设置语言与持久化2项检查通过，Lint0错误27警告。帧采集使用独立ActivityScenario和正常Android帧时钟，避免Compose测试时钟生成无效的零帧报告。
- 权威15节需求SHA256保持不变；源码包与交付包脚本均逐文件校验压缩内容SHA256。具体生成路径及SHA256见artifacts中的包和同名.sha256。

## Windows短时性能
环境：Windows11 Enterprise 10.0.26300、Intel Ultra5 226V（8逻辑线程）、Arc130V集显、普通用户。模拟器与开发后台仍存在，本轮不是无干扰游戏实验。
采集范围为Collector及直接子进程（worker/控制台宿主），探针不计入；前5秒排除，96个进程采样点，约120秒。未调用工作集裁剪或强制GC制造低内存结果。

| 指标 | 实测 |
|---|---|
| 整机口径CPU均值 / p95 / 最大 | 0.133% / 0.650% / 1.091% |
| 合计工作集均值 / p95 / 最大 | 206.36 / 209.78 / 209.96 MiB |
| Private Bytes均值 / 最大 | 39.98 / 43.07 MiB |
| 采样间隔均值 / p95 / 最大 | 1009.10 / 1052.53 / 1078.93 ms |
| CPU传感器Update p95 / 最大 | 36.93 / 76.04 ms |
| GPU传感器Update p95 / 最大 | 0.316 / 0.597 ms |
| 快照JSONL体积 | 160048 bytes / 120帧（约1.33KB/帧，非网络线速） |

CPU短测均值低于1%，不能推广为所有硬件/游戏场景通过。工作集明显超80MB目标，即使Private Bytes较小也不能替代工作集验收。当前缓存GPU元数据和传感器绑定以减少每秒重复分配，仍需进一步架构/加载分析；本轮没有实现最终内存达标。应用JSON大小不包含TLS/TCP/mDNS开销，尚未抓包量测真实网络流量。

原始数据：stage-6-tray-live-{process.csv,snapshots.jsonl,timings.jsonl,result.json,probe.txt}与stage-6-tray-measurements.json。更早baseline采集时有重负载，仅作历史记录。

## Android模拟器渲染测量
API29 x86_64模拟器PCTelemetryQA，2248×1080、420dpi；Debug构建、DEMO输入。每个主题稳定3秒后重置gfxinfo，正常时钟采集15秒，保留原始framestats于docs/qa/performance。此结果包含主机调度与虚拟GPU影响，不能证明实体手机60fps。

| 主题 | 渲染帧 | gfxinfo卡顿帧 | p50 / p95 / p99 |
|---|---:|---:|---|
| SPORT | 371 | 369（99.46%） | 38 / 53 / 61 ms |
| HUD | 680 | 680（100%） | 32 / 46 / 53 ms |

实测未证明60fps目标，模拟器卡顿比例很高。后续需在目标手机以Release/profileable构建复测并分析绘制/调度，不以模拟器测试通过掩盖性能问题。可运行scripts/measure-android-ui.ps1复采。

## 交付与未完成事项
源码、协议、锁文件、构建脚本、设计图、测试记录随源码ZIP提供。预览交付ZIP包含Windows-x64便携目录、Debug APK、使用说明、已知问题、验证报告与依赖许可证。依赖许可按实际NuGet/POM提取，并附.NET运行时官方通知；本项目未代用户选择源码许可证。

未验证：实体小米8/HyperOS、Windows10、AMD/NVIDIA/混合多GPU、两设备真实局域网mDNS、防火墙首次交互、重新登录自启、断网/睡眠/OLED微移实机效果。没有游戏基线，无法给出FPS/1%Low/帧时间影响结论；没有执行完整4小时稳定性，方案保留于tests/README.md。不能将本版本标记为全部验收完成。

.NET8官方维护于2026-11-10结束。已更新当前补丁并保持原需求框架，后续需安排受支持版本迁移及回归。
