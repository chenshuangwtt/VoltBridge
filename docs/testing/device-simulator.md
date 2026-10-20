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

## 手工模式

先在本机启动 VoltBridge，并在数据库中准备对应协议、设备号和枪号。然后从 IDE 运行 `com.voltbridge.testing.simulator.scenario.ManualSimulatorMain`，或按模块 classpath 运行该 main。示例参数：

```text
--protocol=ykc --host=127.0.0.1 --port=9000 --device=20231212000010 --connector=1
--protocol=ocpp --host=127.0.0.1 --port=9100 --device=OCPP-SIM-001 --connector=1 --remote-stop=true
```

CLI 上线后等待操作者调用平台远程启动 API；若使用 `--remote-stop=true`，它在半程等待远程停止 API。可用 `--real-duration-seconds`、`--simulated-duration-minutes` 和 `--progress-interval-seconds` 调整节奏。
