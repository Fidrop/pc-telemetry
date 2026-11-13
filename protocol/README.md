# JSON 协议 v1 草案
WSS /v1/stream，UTF-8 JSON 文本消息，每条不超过 16 KiB。schema 定义 telemetry，其他消息先定义契约，后续阶段补充对应 schema 与跨端编解码测试。
examples 全部为合成 DEMO 测试夹具，未采集本机传感器。

## 通用规则
version=1，type 为消息类型。未知版本拒绝；v1 未知字段按 schema 拒绝，扩展须版本协商。
sequence 为会话内递增安全整数，sessionId 每次服务启动变化；deviceId 持久化随机 UUID，不暴露 MAC。客户端同时验证已配对 deviceId、sessionId、sequence，拒绝旧连接/重放/乱序数据。
sampledAt 为 UTC RFC3339，每个指标独立 sampledAt 和 status。容量整数 bytes，百分比 0–100，温度摄氏度，不依赖本地化字符串。
ok 为本周期成功采样，stale 为保留的过去成功值，unavailable 的值和时间必须为 null。内存不可用时 usedBytes、totalBytes、percent 都为 null；可用时必须全部有值，0≤used≤total，percent=100×used/total，允许 0.05 百分点舍入误差。该跨字段关系需语义校验，schema 不能独自保证。
某项数据不可用不使整条快照无效。NaN/Infinity、越界、类型错误、伪造未来时间和重复序列不得刷新连接计时。单指标过期按自身状态显示，全部指标 stale/unavailable 的快照不能刷新“最后有效数据”时刻。
服务器只将本周期确实完成采样的值标 ok。客户端用单调时钟计收包间隔，避免系统时钟调整造成 5 秒判断错误。远端 UTC 用于历史与诊断，不能仅凭两机时钟差判断离线。

## 消息契约
| type | 方向 | 关键字段与行为 |
|---|---|---|
| hello | 服务端→客户端 | version、deviceId、sessionId、supportedVersions、gpuList，认证前仅最小身份信息 |
| pair.request | 客户端→服务端 | version、requestId、code，已确认 TLS pin 后且配对窗口开放时允许 |
| pair.result | 服务端→客户端 | requestId、credentialId、token，仅成功一次下发 |
| auth | 客户端→服务端 | credentialId、token，认证前禁止订阅 |
| auth.result | 服务端→客户端 | success，失败统一错误，不回显 token |
| gpu.select | 客户端→服务端 | requestId、gpuId，来自 gpuList |
| gpu.selected | 服务端→客户端 | requestId、gpuId，后续 telemetry 明示当前 GPU |
| telemetry | 服务端→客户端 | 见 schema，默认 1 Hz |
| error | 双向 | requestId（可选）、code，无秘密和堆栈 |

最多一个当前 Windows 数据源。切换 GPU 的回执后清空 GPU 历史，不能把不同 GPU 拼成连续曲线。GPU 消失返回明确错误并更新列表，选取新设备须显示切换状态。
GPU 列表只在认证后完整发送。内置 WebSocket ping/pong 可检测连接，但不会刷新有效数据时刻。

## 状态机
新增可选能力：新客户端在WSS握手中发送 `X-PC-Telemetry-Features: device-name`。服务端只对声明该能力的客户端在认证后的hello和成功pair.result中加入 `deviceName`，值为Windows设备名。未声明时保留原字段集合，避免破坏严格JSON旧客户端。新客户端字段可选，因此仍能连接旧Collector。名称仅用于显示，不参与身份认证；仍固定证书指纹与deviceId。已有配对可在收到认证hello后更新名称，不更换token。

未配置地址先 DISCOVERING，手动输入可直接 PAIRING；已有凭据进入 RECONNECTING。
TLS 与 auth 成功后仍等待第一条有效 telemetry 才 CONNECTED。连续 5 秒无有效新数据进入 OFFLINE，保留读数与年龄。
重连尝试使用 1、2、4、8、16、30 秒上限的随机抖动退避，单连接单任务。OFFLINE 标记保留，连接尝试状态另显示 RECONNECTING，不能让重连文案掩盖过期数据。
恢复新会话重置序列检查，接收有效新快照后平滑过渡。首次无数据展示 N/A，年龄未知。
