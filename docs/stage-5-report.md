# 阶段5检查点
2026-11-10。主要产品化代码已保存，综合验收仍有待办。按用户最新指令，完成本检查点推送后暂停。

## 已实现
Windows增加默认托盘入口，包含连接数、自启、配对信息、撤销和退出。自启仅写当前用户Run项，路径带引号。
私有网络地址绑定、回环保留、局域网请求再次检查网络配置文件。mDNS采用Makaretu.Dns.Multicast0.27.0（MIT），限定私有适配器与地址，保留手动输入。
配对码8位、120秒、全窗口5次失败上限、原子单次消费。长期凭据256bit随机，服务端保存DPAPI加密的凭据哈希，支持撤销与已认证unpair。开发凭据仅回环可用。
Android增加NsdManager发现、手动地址、指纹观察、人工核对后配对、安全保存、设置浮层、语言/主题/夜间/微移/GPU选择与重新配对。

## 实际通过
Windows编译0警告0错误，63项检查通过，见stage-5-windows-tests.txt。
暂停前Android共享核心17项单元测试通过。普通运行中的3项受控JVM联调因未提供测试凭据而跳过，本次真实链路由Android10模拟器另外验证，见checkpoint-android-tests.txt。
Android Debug APK构建通过。最近完整Lint为0错误、48警告，涉及未使用资源、版本目录建议与依赖新版本，待清理。
Android10 API29模拟器实际运行，两主题DEMO与滑动检查通过，见stage-3-4-ui-tests.txt。
模拟器经ADB reverse连接真实Windows Collector，完成指纹观察、一次性配对码换长期凭据、真实CPU/GPU/RAM接收。Keystore/DataStore保存后重读一致，存储字节不含明文token。见stage-5-emulator-live-test.txt。
Android10与桌面JDK的Instant解析差异曾导致Windows +00:00时间戳被丢弃，已改用OffsetDateTime并完成真实数据回归。

## 待办与限制
小米8 Android14 HyperOS2、Windows10、AMD/NVIDIA、多GPU实机、真实局域网mDNS、托盘实际点击、自启、防火墙、重配对与OLED微移实机待验证。
网络配置变化后需重启Collector，托盘窗口已有提示。全部设置交互与语言切换尚未逐项验收。
缺失温度与专用VRAM仍为N/A，工作集超80MB尚未优化。阶段6自包含便携发布、最终APK交付、标准性能/游戏/长期稳定性验证未开始。
用户明确说继续后，从上述待办继续，并保持逐次提交推送。
