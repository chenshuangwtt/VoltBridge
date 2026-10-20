package com.voltbridge.protocol.yunkuaichong.command;

import com.voltbridge.common.time.TimeProvider;
import com.voltbridge.device.event.TransactionRecordEvent;
import com.voltbridge.device.model.DeviceId;
import com.voltbridge.protocol.common.ProtocolContext;
import com.voltbridge.protocol.common.ProtocolException;
import com.voltbridge.protocol.common.codec.HexCodec;
import com.voltbridge.protocol.common.spi.ProtocolInboundResult;
import com.voltbridge.protocol.yunkuaichong.codec.YunKuaiChongTransactionRecordAckEncoder;
import com.voltbridge.protocol.yunkuaichong.codec.YunKuaiChongTransactionRecordParser;
import com.voltbridge.protocol.yunkuaichong.message.YunKuaiChongFrame;
import com.voltbridge.protocol.yunkuaichong.message.YunKuaiChongTransactionRecordMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import com.voltbridge.transport.api.BinaryTransportPayload;
import java.util.UUID;

/**
 * 云快充 0x3B 上行适配器，负责协议消息、平台事件和 0x40 应答之间的转换。
 *
 * <p>这里可以预编码成功应答，但网关会先同步分发 {@link TransactionRecordEvent}；只有业务
 * 持久化成功或确认幂等重复后才真正写出 0x40，业务层不会接触二进制协议帧。</p>
 */
@Component
public class YunKuaiChongTransactionRecordHandler implements YunKuaiChongCommandHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(YunKuaiChongTransactionRecordHandler.class);
    private final YunKuaiChongTransactionRecordParser parser;
    private final YunKuaiChongTransactionRecordAckEncoder ackEncoder;
    private final TimeProvider timeProvider;

    public YunKuaiChongTransactionRecordHandler(YunKuaiChongTransactionRecordParser parser,
                                                YunKuaiChongTransactionRecordAckEncoder ackEncoder,
                                                TimeProvider timeProvider) {
        this.parser = parser;
        this.ackEncoder = ackEncoder;
        this.timeProvider = timeProvider;
    }

    @Override public int command() { return YunKuaiChongTransactionRecordParser.TRANSACTION_RECORD_COMMAND; }

    @Override
    public ProtocolInboundResult handle(ProtocolContext context, YunKuaiChongFrame frame) {
        YunKuaiChongTransactionRecordMessage message = parser.parse(frame);
        DeviceId deviceId = requireDevice(context, message.deviceCode());
        // 在协议边界完成 DTO 到标准事件的转换，避免业务模块依赖云快充类型。
        TransactionRecordEvent event = new TransactionRecordEvent(UUID.randomUUID(), deviceId,
                context.protocol(), message.connectorNo(), message.transactionId(), message.startedAt(),
                message.endedAt(), message.startMeterKwh(), message.endMeterKwh(),
                message.totalEnergyKwh(), message.totalAmount(), message.vehicleVin(),
                message.startMethod(), message.stopCategory(), message.rawStopCode(),
                message.rawStopDetail(), message.transactionTime(), message.energyBreakdown(),
                HexCodec.encode(frame.rawFrame()), message.additionalInfo(), timeProvider.now());
        // 这里只准备应答；LocalGatewayConnectionCoordinator 会在同步事件处理成功后发送。
        byte[] successAck = ackEncoder.encode(frame, message.transactionId(), true);
        LOGGER.debug("YunKuaiChong 0x40 ACK prepared: tradeNo={}, deviceCode={}, connectorNo={}",
                message.transactionId(), message.deviceCode(), message.connectorNo());
        return new ProtocolInboundResult(List.of(event), List.of(new BinaryTransportPayload(successAck)), Optional.empty());
    }

    private DeviceId requireDevice(ProtocolContext context, String payloadDeviceCode) {
        DeviceId deviceId = context.deviceId().orElseThrow(() -> new ProtocolException(
                "YKC_TRANSACTION_NOT_AUTHENTICATED", "Transaction record received before login"));
        if (!deviceId.value().equals(payloadDeviceCode)) {
            throw new ProtocolException("YKC_TRANSACTION_DEVICE_MISMATCH", "Transaction record device code mismatch");
        }
        return deviceId;
    }
}
