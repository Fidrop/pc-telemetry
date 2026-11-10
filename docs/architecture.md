# 架构与依赖决策
日期 2026-11-10。版本为固定的初始选型，尚未下载、还原或验证兼容性，未声称为最新版本。首次还原时核对官方发布页、安全公告、传递依赖与许可证，再提交锁文件。

阶段1更新：Windows依赖已实际还原与锁定，Release编译成功。NuGet直接/传递依赖查询未报告已知漏洞，LHM包元数据确认为MPL-2.0。Android选型仍待构建验证。调度已改用单执行者每周期按Stopwatch耗时等待剩余时长，长周期直接跳过错过的时限，避免PeriodicTimer积压与GroupSchedule边界抖动产生补采或误跳采。

## 依赖
| 用途 | 版本 | 理由 |
|---|---|---|
| .NET SDK / 运行目标 | 8.0.408 / net8.0-windows | 遵循需求的 .NET 8，SDK 允许同功能带补丁更新 |
| LibreHardwareMonitorLib | 0.9.4 | 优先硬件访问库，首次还原核对 NuGet 与 MPL-2.0 义务 |
| WinForms / Kestrel / TLS | .NET 8 共享框架 | 托盘、WebSocket 与成熟 TLS，避免自制网络栈 |
| ProtectedData | 8.0.0 | Windows DPAPI CurrentUser |
| JDK / Gradle / AGP | 17 / 8.11.1 / 8.9.1 | 明确工具链组合，阶段 2 实际构建确认 |
| Kotlin / Compose compiler | 2.1.20 | 编译插件同版本 |
| Compose BOM | 2025.03.01 | Compose 组件统一约束 |
| Activity / Lifecycle | 1.10.1 / 2.9.0 | Compose 生命周期与前台收集 |
| DataStore | 1.1.4 | 设置持久化 |
| Coroutines / Serialization | 1.10.1 / 1.8.0 | 数据流与版本化 JSON |
| OkHttp | 4.12.0 | Android TLS 与 WebSocket |
| mDNS | Android NsdManager；Windows DnsServiceRegister 系统 API | 减少依赖，Windows 10 1903+ 自动发现；更早版本保留手动地址 |

.NET 8 生命周期与发布时补丁状态须在阶段 6 再审核，框架升级需用户确认。以上版本未做在线供应链核验。
参考入口 https://learn.microsoft.com/dotnet/ 、https://developer.android.com/build/releases 、https://github.com/LibreHardwareMonitor/LibreHardwareMonitor 。

## 数据管线
Windows HardwareSession → 分组采样缓存 → 快照组装 → 单次 JSON 编码 → 已认证 WebSocket。
Android WebSocket → 协议校验 → 状态机与历史缓冲 → ViewModel → Canvas。
采集层不依赖网络或 UI。选择 GPU 仅允许选择已枚举的稳定标识，无通用命令接口。

## Windows 调度与隔离
- 启动枚举 Hardware/SubHardware，保存硬件标识、传感器标识、单位、候选优先级与所属 Update 组。GPU 优先有专用显存的独显，无法确定时要求选择，稳定 ID 持久化。
- CPU 使用率优先总负载，温度优先 Package 后可识别的 Tctl/Tdie，再到有效核心最大值并标记来源。GPU 核心负载和核心温度优先厂商对应传感器。RAM 使用 GlobalMemoryStatusEx，VRAM 仅接受专用容量。未验证映射返回 unavailable，绝不把共享内存当专用显存。
- 一个硬件组每周期至多 Update 一次，再读取缓存中的所有目标值。禁止按指标重复刷新同一组。1 Hz PeriodicTimer，单执行者，无任务重入，周期超时跳过追赶。
- LHM 同步 Update 无可靠取消机制。拟用一个受控后台采集子进程持有库对象，托盘/网络进程通过本地受限管道获得缓存。正常情况下单 worker，禁止每个传感器创建进程。
- 按组记录 Stopwatch 耗时，连续三次超过 250 ms 降为每 5 秒采样并标记 stale。worker 2 秒无响应视为卡死，由 supervisor 终止并等待退出，退避后重建，跳过最后开始的硬件组。避免将 Task 超时误当作已终止调用。
- 隔离组每 60 秒允许一次恢复探测，其他组继续使用独立缓存。全局仍仅一个 worker，探测卡死时重启，其间发布 stale/unavailable。记录恢复与故障事件并限频。具体阈值为待实测的初值。
- 正常采样到快照只有最新值，无无界队列。每客户端发送队列容量 1，慢客户端丢旧快照并最终断开，限制连接数量。JSON 每秒生成一次复用。
- 无需实时 3D。日志仅连接变化、异常与低频汇总。退出取消计时、关闭客户端、关闭硬件并等待 worker，超时回收。
- CPU 与 80 MB 工作集目标包含托盘和 worker 总和，架构额外内存须实测。若不达标，依据耗时与内存分析调整，不能省略卡死隔离而宣称满足要求。

## Android 数据与绘制
网络 1 Hz 更新 StateFlow，前台按显示刷新节奏插值，约 300–600 ms 到达目标。每帧的数字、指针和灯带共用同一插值值。
缓存刻度 Path、画笔、文字布局，绘制层隔离装饰动画。后台停止装饰与前台连接，恢复时重新判断数据年龄。
60 秒环形缓冲按采集时间存储有效原始采样，容量 60（协议固定 1 Hz），去重与乱序过滤。缺失/断线区间留空，不补造采样。
DataStore 保存主题、语言、GPU、夜间与微移设置。Keystore AES-GCM 密钥保护凭据密文，退出配对清除密文与 pin。
