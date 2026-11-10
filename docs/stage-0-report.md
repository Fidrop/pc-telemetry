# 阶段 0 报告
后续进展：用户已确认继续，阶段1状态见stage-1-report.md，下文保留阶段0检查记录。
日期 2026-11-10。阶段 0 文件整理与可运行检查完成，本次补充原图后已完成逐图核对。等待用户审阅，未进入阶段 1。

## 需求溯源
read_thread 成功返回完整消息 9358617d-c8fe-4735-b7cc-eafb3b3599a0，来自对话 6af26f86-2ba0-83ec-a5e5-4494d6b13353。
source-message.md 保留整条消息，包括十五节总引导词和前后说明；provenance.json 保存来源标识。SHA-256 记录见 baseline.sha256。
本次工作边界来自用户明确的“仅阶段0”授权。

## 实际检查
- 初始 D:\perf_monitor 为空，无 Git 仓库，无已有工作被覆盖。D:\AGENTS.md 与项目 AGENTS.md 未发现。
- dotnet --info 显示 host 10.0.9、多个运行时（含 8.0.14），没有任何 .NET SDK。
- PATH 未发现 java、gradle、python、node。常见 Program Files/Android、Java、LocalAppData/Android 及检查过的 D 盘 Android 目录未返回安装项。不能据此排除其他自定义安装路径。
- ANDROID_HOME、ANDROID_SDK_ROOT、JAVA_HOME 未发现。Android SDK/JDK/Gradle 工具链未确认可用。
- git 可用，git status 明确返回非 Git 仓库。本阶段没有初始化提交历史。
- adb 可用。首次受限执行无法启动服务，获准重试后启动成功，设备列表为空。检查启动了本机 adb 服务。
- CIM 首次权限受限，获准重试成功。详细硬件与操作系统见 environment.json。
- 原对话 attachments=[]，初次检查无图片。本次用户在 design_ref 补充两张1774×887原图，已查看并记录摘要与SHA-256。

## 已创建的工程内容
android 根构建配置与版本目录；windows 项目配置；protocol schema 与两份 DEMO 夹具；docs 完整需求、架构、安全、视觉、验收与本报告；tests 实测方案；scripts 阶段0检查；README 与忽略规则。
当前工程配置尚未还原或编译。没有 app 模块、采集实现、真实数据服务器或安装产物。

## 主要设计选择（待审阅）
WSS + 首次人工核对证书指纹 + 一次性配对码 + 加密保存长期凭据。
单后台采集 worker 便于回收不可取消的硬件调用，整机 CPU 和工作集统计包含所有进程。
Android 1Hz数据与显示刷新解耦，Canvas原生绘制，默认可用无第三方商标替代素材。
版本固定为初始依赖方案，尚未进行在线版本/安全公告/还原验证。

## 检查结果与限制
scripts/check-stage0.ps1 的实际输出见 stage-0-check.txt。该脚本仅检查基线节数、目录、JSON夹具与部分语义，不等同完整 JSON Schema 验证或生产协议测试。
没有进行 APK/EXE 构建、真实传感器兼容测试、FPS 对照或 4 小时稳定性测试。缺 SDK、手机连接和游戏实验环境。
CPU≤1%、工作集≤80MB、游戏平均FPS影响≤1% 均为待实测优化目标。

## 需用户确认
- 审阅依赖、WSS 首次指纹核对流程、worker 隔离方案与阶段1计划。
- 原图核对已完成，ROG 独立素材可选，当前采用可替换资源方案。
- 明确确认阶段 0 后才进入阶段 1。跨设备接续说明见 docs/handoff.md。
