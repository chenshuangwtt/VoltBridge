package com.voltbridge.protocol.ocpp16.action;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.voltbridge.common.time.TimeProvider;
import com.voltbridge.device.model.ChargingStartMethod;
import com.voltbridge.device.model.DeviceId;
import com.voltbridge.device.model.DeviceTransactionStartRequest;
import com.voltbridge.device.model.DeviceTransactionStartResult;
import com.voltbridge.device.model.DeviceProtocol;
import com.voltbridge.device.port.DeviceTransactionStartService;
import com.voltbridge.protocol.common.ProtocolContext;
import com.voltbridge.protocol.common.spi.ProtocolInboundResult;
import com.voltbridge.protocol.ocpp16.Ocpp16Exception;
import com.voltbridge.protocol.ocpp16.codec.Ocpp16FrameCodec;
import com.voltbridge.protocol.ocpp16.message.Ocpp16Call;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 处理桩端 StartTransaction；远程启动和本地启动都复用同一交易聚合，不伪造平台命令。
 *
 * <p>RemoteStartTransaction Accepted 只确认控制请求；本处理器调用统一业务端口后，交易才有
 * 真实开始证据并进入 CHARGING。返回的整数 transactionId 是 OCPP 协议交易号。</p>
 */
@Component
public class StartTransactionHandler extends Ocpp16ActionSupport implements Ocpp16ActionHandler {

    private static final BigDecimal WH_PER_KWH = BigDecimal.valueOf(1000);
    private final DeviceTransactionStartService transactionStartService;
    private final TimeProvider timeProvider;

    public StartTransactionHandler(Ocpp16FrameCodec codec,
                                   DeviceTransactionStartService transactionStartService,
                                   TimeProvider timeProvider) {
        super(codec);
        this.transactionStartService = transactionStartService;
        this.timeProvider = timeProvider;
    }

    @Override
    public String action() {
        return "StartTransaction";
    }

    @Override
    public ProtocolInboundResult handle(ProtocolContext context, Ocpp16Call call) {
        DeviceId deviceId = activeDevice(context, call.uniqueId());
        int connectorNo = requiredInt(call.payload(), "connectorId", call.uniqueId());
        int meterStart = requiredInt(call.payload(), "meterStart", call.uniqueId());
        requiredText(call.payload(), "idTag", call.uniqueId());
        if (connectorNo <= 0 || meterStart < 0) {
            throw new Ocpp16Exception(call.uniqueId(), "PropertyConstraintViolation",
                    "connectorId must be positive and meterStart must not be negative");
        }
        Instant now = timeProvider.now();
        Instant startedAt = instant(call.payload(), "timestamp", call.uniqueId(), now);
        // PROTOCOL DTO -> canonical transaction start request，业务层不依赖 OCPP payload。
        DeviceTransactionStartResult result = transactionStartService.start(new DeviceTransactionStartRequest(
                deviceId, DeviceProtocol.OCPP_16, connectorNo, startedAt,
                BigDecimal.valueOf(meterStart).divide(WH_PER_KWH), ChargingStartMethod.UNKNOWN, now));

        ObjectNode idTagInfo = object().put("status", result.accepted() ? "Accepted" : "Invalid");
        ObjectNode response = object().set("idTagInfo", idTagInfo);
        response.put("transactionId", Math.toIntExact(result.protocolTransactionId()));
        return reply(call.uniqueId(), response);
    }
}
