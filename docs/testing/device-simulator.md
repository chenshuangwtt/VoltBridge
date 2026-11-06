# 设备模拟器

`voltbridge-testing` 中的模拟器以外部充电桩的身份通过真实网络连接驱动 VoltBridge。它不会调用生产环境的协议处理器或注入 simulation mode：YKC 使用真实 TCP 二进制帧，OCPP 使用带 `ocpp1.6` 子协议的真实 WebSocket JSON 消息。

## 组成

| 区域 | 类 | 职责 |
|---|---|---|
| 核心 | `DeviceSimulator` | 协议无关的连接、登录、心跳、进度、完成与断开操作 |
| 场景 | `ChargingScenarioRunner` | 驱动远程启动、可重复进度和自然/远程停止收敛 |
| 数据 | `ChargingScenarioConfig`、`DeterministicChargingProfile` | 配置真实/虚拟时间及线性 SOC、电量、电表曲线 |
| YKC | `YunKuaiChongV150Simulator` | TCP、`0x01/0x34/0x33/0x19/0x3B/0x40` |
| OCPP | `Ocpp16Simulator` | WebSocket、Boot、RemoteStart/Stop、Start/StopTransaction、MeterValues |
| 结果 | `ScenarioResult`、`ScenarioTrace` | 可断言的最终快照和人类可读事件轨迹 |

## 时间加速与确定性

`realDuration` 决定测试真实等待节奏，`simulatedDuration` 决定协议消息中的虚拟充电时长和时间戳。例如自动化场景以 `300ms` 真实时间模拟 `30min` 充电；手工模式默认 `30s` 真实时间模拟 `30min`。

同一 `ChargingScenarioConfig` 会生成相同数量的样本和相同的线性值：SOC 从 `initialSoc` 走到 `targetSoc`，累计电量从 `initialEnergyKwh` 走到 `targetEnergyKwh`，电表值为 `initialMeterKwh + energyKwh`。电压使用小幅确定性阶梯，电流由功率/电压计算。没有随机数。

## 自动化场景

运行完整验证：

```powershell
mvn clean package
```

仅运行模拟器场景：

```powershell
mvn -pl voltbridge-monolith-bootstrap -am "-Dtest=YunKuaiChongSimulatorScenarioIntegrationTest,Ocpp16SimulatorScenarioIntegrationTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

场景测试使用真实 `NettyTcpServer` 或 `NettyWebSocketServer`，并通过 `DeviceController` 这一 REST 输入适配器调用现有的 `StartChargingUseCase` / `StopChargingUseCase`。仓储断言只验证命令、交易和运行态最终结果。

## 云快充流程

1. TCP 连接后发送登录、心跳和连接器就绪实时帧。
2. 平台发出 `0x34` 后，模拟器验证设备/枪号与 tradeNo，回复 `0x33` 成功。
3. 连续 `0x13` 实时数据推进 `ACKED -> SUCCEEDED` 和 `STARTING -> CHARGING`。
4. 自然完成发送 `0x19` 再发送 `0x3B`；远程停止则先接收 `0x36`、回复 `0x35`，随后仍发送 `0x19/0x3B`。
5. 模拟器验证每条 `0x3B` 都得到 `0x40`；重复最终记录仍应得到成功 ACK，但不得创建第二笔交易。

## OCPP 流程

1. WebSocket 使用 `ocpp1.6` 连接，发送 BootNotification、Heartbeat、StatusNotification。
2. 接收 RemoteStartTransaction 并回复 Accepted，然后通过 StartTransaction 获取平台分配的数值 `transactionId`。
3. 连续发送 MeterValues；平台命令状态仅在真实 StartTransaction 后成为 `SUCCEEDED`。
4. 自然完成直接发 StopTransaction；远程停止先对 RemoteStopTransaction 回复 Accepted，再发 StopTransaction。最终交易只由 StopTransaction 收敛。
5. 本地启动测试直接发 StartTransaction，不创建平台 `DeviceCommand`。

## 失败与诊断

每个模拟器保存 `ScenarioTrace`，失败信息会附带已发生的网络事件。典型轨迹：

```text
[00:00] TCP connected: 127.0.0.1:9000
[00:00] Login accepted: deviceCode=20231212000010
[00:00] RemoteStart received: tradeNo=...
[00:00] Charging started: SOC=30 energy=1.2000kWh
[00:00] Progress: SOC=50 energy=3.0000kWh
[00:00] EndCharge sent
[00:00] Final TransactionRecord acknowledged
[00:00] Platform transaction COMPLETED
```

已覆盖的轻量故障场景包括云快充重复 `0x3B` 与充电中断线。断线只会使设备离线，交易保持 `CHARGING`，不会被错误完成。模拟器接口保留了命令等待和网络连接控制点，可继续扩展为延迟响应、无响应或晚到响应。

## 独立运行模拟器

在仓库根目录构建可执行 fat JAR：

```powershell
.\mvnw.cmd -pl voltbridge-testing -am -Psimulator package -DskipTests
java -jar .\voltbridge-testing\target\voltbridge-device-simulator.jar --help
```

`--help` 会列出参数、默认值和输入范围。参数必须使用 `--key=value`；未知参数或无效端口、枪号、布尔值会打印错误并以非零状态退出。程序复用 `voltbridge-testing` 中现有的 `DeviceSimulator`、`Ocpp16Simulator` 和 `YunKuaiChongV150Simulator`，不会引入另一套协议客户端。

手工模拟器默认保持同一条 TCP/WebSocket 连接：每次交易完成后连接器回到 `AVAILABLE`，设备仍保持 `ONLINE`，程序继续等待下一次 RemoteStart。OCPP 心跳采用 BootNotification 返回的周期，YKC 心跳默认每 30 秒发送一次；可用 `--heartbeat-interval-seconds=N` 覆盖。按 `Ctrl+C` 才会主动断开。临时只跑一笔时可设置 `--exit-after-cycle=true`。

## Available simulator devices

开发 seed 将数据展示设备与真实协议联调设备分开。`VB-DEMO-*` 用于 Console 列表、历史交易和账单展示；以下六台设备专门供独立 Simulator 通过真实 TCP/WebSocket 登录。它们各有 connector `1`，初始业务状态为 `ENABLED`、connector 状态为 `AVAILABLE`，seed 不创建命令、交易或 ONLINE 状态。启动并成功登录后，在线状态才会由设备连接更新。

| Protocol | Device code | Connector | Endpoint |
|---|---|---:|---|
| OCPP 1.6J | `OCPP-SIM-001` | 1 | WebSocket `127.0.0.1:9100/ocpp` |
| OCPP 1.6J | `OCPP-SIM-002` | 1 | WebSocket `127.0.0.1:9100/ocpp` |
| OCPP 1.6J | `OCPP-SIM-003` | 1 | WebSocket `127.0.0.1:9100/ocpp` |
| YunKuaiChong V1.5 | `20231212000010` | 1 | TCP `127.0.0.1:9000` |
| YunKuaiChong V1.5 | `20231212000011` | 1 | TCP `127.0.0.1:9000` |
| YunKuaiChong V1.5 | `20231212000012` | 1 | TCP `127.0.0.1:9000` |

先从仓库根目录构建模拟器，再在每个独立 PowerShell 终端切换到 JAR 所在目录。启动第二台时另开一个终端；OCPP 的 `001` 与 `002`、以及 YKC 的 `...10` 与 `...11` 已纳入并行连接测试。

```powershell
.\mvnw.cmd -pl voltbridge-testing -am -Psimulator package -DskipTests
Set-Location .\voltbridge-testing\target
```

OCPP 示例（每台设备各开一个终端）：

```powershell
java -jar .\voltbridge-device-simulator.jar `
  --protocol=ocpp `
  --host=127.0.0.1 `
  --port=9100 `
  --device=OCPP-SIM-001 `
  --connector=1 `
  --remote-stop=true
```

```powershell
java -jar .\voltbridge-device-simulator.jar `
  --protocol=ocpp `
  --host=127.0.0.1 `
  --port=9100 `
  --device=OCPP-SIM-002 `
  --connector=1 `
  --remote-stop=true
```

YunKuaiChong V1.5 示例（每台设备各开一个终端）：

```powershell
java -jar .\voltbridge-device-simulator.jar `
  --protocol=ykc `
  --host=127.0.0.1 `
  --port=9000 `
  --device=20231212000010 `
  --connector=1 `
  --remote-stop=true
```

```powershell
java -jar .\voltbridge-device-simulator.jar `
  --protocol=ykc `
  --host=127.0.0.1 `
  --port=9000 `
  --device=20231212000011 `
  --connector=1 `
  --remote-stop=true
```

`OCPP-SIM-003` 和 `20231212000012` 可通过相同命令分别替换 `--device` 值启动。以上命令要求对应协议 Gateway 正在监听示例地址；`Ctrl+C` 关闭模拟设备。

## Console 手工测试：OCPP

1. 启动本地 VoltBridge 和 PostgreSQL，并按本地开发流程应用开发 seed。确认 WebSocket OCPP 监听 `127.0.0.1:9100/ocpp`。
2. 确认设备 `VB-DEMO-006` 已存在、协议为 OCPP 1.6J、连接器 `01` 已登记。dev seed 会让该连接器没有 `STARTING`、`CHARGING` 或 `ENDING` 交易，用于手工启动测试。
3. 在另一个终端启动设备模拟器：

   ```powershell
   java -jar .\voltbridge-testing\target\voltbridge-device-simulator.jar `
     --protocol=ocpp --host=127.0.0.1 --port=9100 `
     --device=VB-DEMO-006 --connector=1 --remote-stop=true
   ```

4. 等待输出 `Device online and connector ready`，并在 VoltBridge Console 确认该设备在线。每笔交易完成后控制台会显示 `Device remains online` 并等待下一次 RemoteStart。
5. 在 Console 对 `VB-DEMO-006` 的连接器 `01` 发起启动。模拟器会接收 RemoteStartTransaction、回报 StartTransaction 并发送 MeterValues。
6. 模拟器输出进度并等待 RemoteStop。在 Console 对同一连接器停止充电；模拟器完成 StopTransaction，VoltBridge 应收敛交易并生成账单。

自然完成流程可去掉 `--remote-stop=true`；模拟器会按设定的真实时长发送结束帧。测试 YunKuaiChong V1.5 over TCP 时使用 `--protocol=ykc --port=9000 --device=20231212000010`，并确认设备已按 YKC 协议登记。

常用可选参数还有 `--real-duration-seconds`、`--simulated-duration-minutes` 和 `--progress-interval-seconds`。默认 OCPP 目标为 `127.0.0.1:9100`，默认 YKC 目标端口为 `9000`；`--host`、`--port` 可指向其他本机或开发环境地址。
