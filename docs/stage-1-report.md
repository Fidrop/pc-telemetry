# 阶段1报告
日期2026-11-10。用户已确认继续，本次执行Windows数据采集与通信阶段，等待确认进入阶段2。

## 已实现
- 可构建运行的C#控制台入口、参数校验与退出清理。
- LibreHardwareMonitor CPU/GPU传感器、GetSystemTimes CPU回退、GlobalMemoryStatusEx RAM。
- CPU总负载、Package/Tctl/Tdie优先温度与最热核心回退、GPU核心负载/温度、专用VRAM容量与百分比。缺失为null/unavailable，旧缓存标stale。
- GPU稳定ID、独显优先、手动切换与持久化；回执后抑制旧GPU在途快照，避免串接。
- 启动枚举，首次Update后缓存传感器引用，每组每周期只Update一次，串行worker无并发重入。
- 慢组与异常降频、2秒看门狗、卡死worker回收、60秒隔离后恢复探测。初始化单独20秒超时，使用受限命名管道与Job Object。
- 默认约1Hz，超时丢弃错过的时限。已修复实测发现的边界抖动误跳采。
- 版本1严格JSON、回环WSS、TLS证书固定、DPAPI开发凭据、认证后发送、16KiB消息上限、4连接上限、命令限速、有界最新快照队列。
- 构建、自动检查、真实采样、进程测量、Schema检查与打包校验脚本及依赖锁文件。

## 实际执行
使用项目.tools内.NET SDK8.0.408，还原并编译Release，0警告0错误。
scripts/build-windows.ps1 -Test通过50项检查，日志见stage-1-build-and-tests.txt。覆盖映射、缺失、容量、GPU选择、序列化、非法协议、DPAPI、TLS错误pin、认证、WSS、GPU在途帧及worker卡死回收/隔离。
Schema检查覆盖2份DEMO夹具与35份真实快照。测试程序的故障注入明确属于合成测试。
普通权限在Intel Core Ultra5 226V/Intel Arc130V上执行35次采样，客户端通过固定证书接收8条合法live数据，Collector/Probe退出码均0。原始记录见stage-1-live相关文件。
短测数据见stage-1-measurements.json，记录采样间隔、CPU、工作集和分组Update耗时。统计包括Collector、worker与控制台宿主，探针单独运行。诊断输出开启，正式性能验收须关闭诊断后重测。
本次35次快照平均间隔1010.8ms，GPU读数35次均为ok，未发生worker重启。排除前5秒后的26个进程测点，整机口径CPU均值0.337%，工作集均值171.60MiB、最大175.27MiB。CPU组Update均值5.27ms、最大15.79ms，GPU组均值0.23ms、最大0.55ms。以上为短测结果。
NuGet直接/传递依赖查询没有报告已知漏洞，结果不覆盖框架、硬件驱动或未来公告，发布前需更新受支持补丁并重测。

## 已知问题与待验证
工作集超过80MB目标，阶段6需剖析与优化。CPU≤1%与游戏影响≤1%仍待标准基线对照，短测不构成最终验收。
本机CPU/GPU使用率和RAM可用，CPU/GPU温度与可确认的专用VRAM未返回，显示N/A。集成GPU共享内存未冒充专用显存。
未验证管理员温度回退、AMD/NVIDIA、多GPU实机、Windows10、手机链路、FPS/1%Low/帧时间及4小时稳定性。
Windows自签名TLS使用默认密钥集，已修复临时密钥集导致的握手失败，也修复认证拒绝回执发送后的关闭流程，均已回归。
本次另修正性能脚本的浮点CPU差值计算，最终统计以最新JSON和CSV为准。

## 交付与后续
源码位于windows/Collector，自动检查位于tests/Collector.Tests。源码包保留设计图，排除凭据、SDK和编译输出。
当前为阶段1开发构建。正式自包含便携发布归阶段6；托盘、自启、mDNS与首次配对归阶段5。项目SDK未加入系统PATH。
阶段2计划创建Android app，接入JSON解析、状态机、5秒离线与数据年龄、退避重连、60秒缓冲、独立DEMO与简易展示。双端实机前使用固定证书和受控测试凭据。
每阶段完成后继续更新源码压缩包并等待用户确认。
