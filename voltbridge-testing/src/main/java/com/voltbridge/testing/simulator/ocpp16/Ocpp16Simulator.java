package com.voltbridge.testing.simulator.ocpp16;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltbridge.device.model.DeviceProtocol;
import com.voltbridge.testing.simulator.core.ChargingSample;
import com.voltbridge.testing.simulator.core.CompletionMode;
import com.voltbridge.testing.simulator.core.DeviceSimulator;
import com.voltbridge.testing.simulator.scenario.ScenarioTrace;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.ConcurrentHashMap;

/** 使用 JDK WebSocket 客户端扮演 OCPP 1.6J Charge Point 的真实网络模拟器。 */
public final class Ocpp16Simulator implements DeviceSimulator {

    private final URI endpoint;
    private final String deviceCode;
    private final int connectorNo;
    private final ScenarioTrace trace = new ScenarioTrace();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final QueueingListener listener = new QueueingListener();
    private final Set<String> heartbeatRequests = ConcurrentHashMap.newKeySet();
    private int messageSequence;
    private int transactionId;
    private Duration heartbeatInterval = Duration.ofSeconds(60);
    private WebSocket socket;
    private JsonNode remoteStart;
    private JsonNode remoteStop;

    public Ocpp16Simulator(String host, int port, String path, String deviceCode, int connectorNo) {
        this.endpoint = URI.create("ws://" + host + ':' + port + path + '/' + deviceCode);
        this.deviceCode = deviceCode;
        this.connectorNo = connectorNo;
    }

    @Override public DeviceProtocol protocol() { return DeviceProtocol.OCPP_16; }
    @Override public ScenarioTrace trace() { return trace; }

    @Override
    public void connect() throws Exception {
        socket = HttpClient.newHttpClient().newWebSocketBuilder().subprotocols("ocpp1.6")
                .connectTimeout(Duration.ofSeconds(5)).buildAsync(endpoint, listener).get(5, TimeUnit.SECONDS);
        if (!"ocpp1.6".equals(socket.getSubprotocol())) throw new IllegalStateException("OCPP subprotocol not accepted");
        trace.add("WebSocket connected: " + endpoint);
    }

    @Override
    public void loginOrBoot() throws Exception {
        String uniqueId = nextId("boot");
        sendCall(uniqueId, "BootNotification", "{\"chargePointVendor\":\"VoltBridge\",\"chargePointModel\":\"Simulator\"}");
        JsonNode result = receive();
        requireResult(result, uniqueId, "BootNotification");
        if (!"Accepted".equals(result.get(2).path("status").asText())) throw new IllegalStateException("BootNotification rejected");
        long intervalSeconds = result.get(2).path("interval").asLong(60);
        if (intervalSeconds > 0) heartbeatInterval = Duration.ofSeconds(intervalSeconds);
        trace.add("BootNotification accepted: deviceCode=" + deviceCode);
    }

    @Override
    public void heartbeat() throws Exception {
        String uniqueId = nextId("heartbeat");
        sendCall(uniqueId, "Heartbeat", "{}");
        requireResult(receive(), uniqueId, "Heartbeat");
        trace.add("Heartbeat acknowledged");
    }

    @Override
    public void sendHeartbeat() {
        String uniqueId = nextId("heartbeat");
        heartbeatRequests.add(uniqueId);
        sendCall(uniqueId, "Heartbeat", "{}");
        trace.add("Heartbeat sent");
    }

    @Override
    public Duration heartbeatInterval() {
        return heartbeatInterval;
    }

    @Override
    public void connectorReady() throws Exception {
        String uniqueId = nextId("ready");
        sendCall(uniqueId, "StatusNotification", "{\"connectorId\":" + connectorNo
                + ",\"errorCode\":\"NoError\",\"status\":\"Available\"}");
        requireResult(receive(), uniqueId, "StatusNotification Available");
        trace.add("Connector ready: connectorNo=" + connectorNo);
    }

    @Override
    public void awaitRemoteStart(Duration timeout) throws Exception {
        if (!awaitRemoteStartIfAvailable(timeout)) {
            throw new TimeoutException("Timed out waiting for OCPP RemoteStartTransaction");
        }
    }

    @Override
    public boolean awaitRemoteStartIfAvailable(Duration timeout) throws Exception {
        remoteStart = receiveAvailable(timeout);
        if (remoteStart == null) return false;
        requireCall(remoteStart, "RemoteStartTransaction");
        if (remoteStart.get(3).path("connectorId").asInt() != connectorNo) {
            throw new IllegalStateException("RemoteStart connector mismatch");
        }
        trace.add("RemoteStartTransaction received");
        return true;
    }

    @Override
    public void acceptRemoteStart() {
        sendResult(remoteStart.get(1).asText(), "{\"status\":\"Accepted\"}");
        trace.add("RemoteStartTransaction accepted");
    }

    @Override
    public void beginCharging(ChargingSample sample) throws Exception {
        beginTransaction(sample, "SIMCARD001");
    }

    /** 设备侧本地刷卡启动，不依赖平台先发送 RemoteStartTransaction。 */
    public void beginLocalCharging(ChargingSample sample, String idTag) throws Exception {
        beginTransaction(sample, idTag);
    }

    private void beginTransaction(ChargingSample sample, String idTag) throws Exception {
        String uniqueId = nextId("start");
        sendCall(uniqueId, "StartTransaction", "{\"connectorId\":" + connectorNo
                + ",\"idTag\":\"" + idTag + "\",\"meterStart\":" + sample.meterKwh().subtract(sample.energyKwh())
                .movePointRight(3).intValue() + ",\"timestamp\":\"" + sample.occurredAt() + "\"}");
        JsonNode result = receive();
        requireResult(result, uniqueId, "StartTransaction");
        transactionId = result.get(2).path("transactionId").asInt();
        if (transactionId <= 0) throw new IllegalStateException("StartTransaction did not return transactionId");
        sendStatus("Charging");
        trace.add("Transaction CHARGING: transactionId=" + transactionId);
    }

    @Override
    public void sendChargingProgress(ChargingSample sample) throws Exception {
        String uniqueId = nextId("meter");
        int energyWh = sample.meterKwh().movePointRight(3).intValue();
        int powerWatts = sample.powerKw().movePointRight(3).intValue();
        sendCall(uniqueId, "MeterValues", "{\"connectorId\":" + connectorNo + ",\"transactionId\":" + transactionId
                + ",\"meterValue\":[{\"timestamp\":\"" + sample.occurredAt() + "\",\"sampledValue\":["
                + "{\"value\":\"" + energyWh + "\",\"measurand\":\"Energy.Active.Import.Register\",\"unit\":\"Wh\"},"
                + "{\"value\":\"" + powerWatts + "\",\"measurand\":\"Power.Active.Import\",\"unit\":\"W\"},"
                + "{\"value\":\"" + sample.voltage() + "\",\"measurand\":\"Voltage\",\"unit\":\"V\"},"
                + "{\"value\":\"" + sample.current() + "\",\"measurand\":\"Current.Import\",\"unit\":\"A\"},"
                + "{\"value\":\"" + sample.stateOfCharge() + "\",\"measurand\":\"SoC\",\"unit\":\"Percent\"}]}]}");
        requireResult(receive(), uniqueId, "MeterValues");
        trace.add("Progress: SOC=" + sample.stateOfCharge() + " energy=" + sample.energyKwh() + "kWh");
    }

    @Override
    public void awaitRemoteStop(Duration timeout) throws Exception {
        remoteStop = receive(timeout);
        requireCall(remoteStop, "RemoteStopTransaction");
        if (remoteStop.get(3).path("transactionId").asInt() != transactionId) {
            throw new IllegalStateException("RemoteStop transaction mismatch");
        }
        trace.add("RemoteStopTransaction received");
    }

    @Override
    public void acceptRemoteStop() {
        sendResult(remoteStop.get(1).asText(), "{\"status\":\"Accepted\"}");
        trace.add("RemoteStopTransaction accepted");
    }

    @Override
    public void completeCharging(ChargingSample finalSample, CompletionMode mode, boolean duplicateFinalRecord) throws Exception {
        String uniqueId = nextId("stop");
        int meterStop = finalSample.meterKwh().movePointRight(3).intValue();
        String reason = mode == CompletionMode.REMOTE_STOP ? "Remote" : "Other";
        sendCall(uniqueId, "StopTransaction", "{\"transactionId\":" + transactionId + ",\"meterStop\":" + meterStop
                + ",\"timestamp\":\"" + finalSample.occurredAt() + "\",\"reason\":\"" + reason + "\"}");
        requireResult(receive(), uniqueId, "StopTransaction");
        sendStatus("Available");
        trace.add("StopTransaction finalized");
    }

    @Override
    public void disconnect() {
        if (socket != null) {
            try {
                socket.sendClose(WebSocket.NORMAL_CLOSURE, "scenario complete").join();
            } catch (java.util.concurrent.CompletionException closedByPeer) {
                // A peer may already have closed after a failed/finished scenario; cleanup stays idempotent.
            }
            trace.add("WebSocket disconnected");
            socket = null;
        }
    }

    private void sendStatus(String status) throws Exception {
        String uniqueId = nextId("status");
        sendCall(uniqueId, "StatusNotification", "{\"connectorId\":" + connectorNo
                + ",\"errorCode\":\"NoError\",\"status\":\"" + status + "\"}");
        requireResult(receive(), uniqueId, "StatusNotification " + status);
    }

    private synchronized String nextId(String type) { return "sim-" + type + '-' + (++messageSequence); }
    private void sendCall(String uniqueId, String action, String payload) { send("[2,\"" + uniqueId + "\",\"" + action + "\"," + payload + "]"); }
    private void sendResult(String uniqueId, String payload) { send("[3,\"" + uniqueId + "\"," + payload + "]"); }
    private synchronized void send(String message) { socket.sendText(message, true).join(); }

    private JsonNode receive() throws Exception { return receive(Duration.ofSeconds(5)); }
    private JsonNode receive(Duration timeout) throws Exception {
        JsonNode message = receiveAvailable(timeout);
        if (message == null) throw new IllegalStateException("Timed out waiting for OCPP server message; trace=" + trace.render());
        return message;
    }

    private JsonNode receiveAvailable(Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            long remainingNanos = deadline - System.nanoTime();
            if (remainingNanos <= 0) return null;
            String message = listener.messages.poll(remainingNanos, TimeUnit.NANOSECONDS);
            if (message == null) return null;
            if (QueueingListener.CLOSED_MESSAGE.equals(message)) {
                throw new java.io.EOFException("OCPP WebSocket closed by VoltBridge");
            }
            JsonNode parsed = objectMapper.readTree(message);
            String uniqueId = parsed.path(1).asText();
            if (parsed.path(0).asInt() == 3 && heartbeatRequests.remove(uniqueId)) {
                trace.add("Heartbeat acknowledged: currentTime=" + parsed.path(2).path("currentTime").asText());
                continue;
            }
            return parsed;
        }
    }

    private void requireResult(JsonNode message, String uniqueId, String action) {
        if (message.path(0).asInt() != 3 || !uniqueId.equals(message.path(1).asText())) {
            throw new IllegalStateException(action + " expected CALLRESULT for " + uniqueId + " but received " + message);
        }
    }

    private void requireCall(JsonNode message, String action) {
        if (message.path(0).asInt() != 2 || !action.equals(message.path(2).asText())) {
            throw new IllegalStateException("Expected OCPP " + action + " but received " + message);
        }
    }

    private static final class QueueingListener implements WebSocket.Listener {
        private final LinkedBlockingQueue<String> messages = new LinkedBlockingQueue<>();
        private final StringBuilder current = new StringBuilder();
        @Override public void onOpen(WebSocket webSocket) { webSocket.request(1); }
        @Override public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            current.append(data);
            if (last) { messages.add(current.toString()); current.setLength(0); }
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }
        @Override public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            messages.offer(CLOSED_MESSAGE);
            return CompletableFuture.completedFuture(null);
        }
        @Override public void onError(WebSocket webSocket, Throwable error) {
            messages.offer(CLOSED_MESSAGE);
        }

        private static final String CLOSED_MESSAGE = "__VOLTBRIDGE_WEBSOCKET_CLOSED__";
    }
}
