package com.voltbridge.protocol.yunkuaichong.command;

import com.voltbridge.common.time.TimeProvider;
import com.voltbridge.device.event.ChargingEndedEvent;
import com.voltbridge.device.model.DeviceId;
import com.voltbridge.protocol.common.ProtocolContext;
import com.voltbridge.protocol.common.ProtocolException;
import com.voltbridge.protocol.common.spi.ProtocolInboundResult;
import com.voltbridge.protocol.yunkuaichong.codec.YunKuaiChongEndChargeParser;
import com.voltbridge.protocol.yunkuaichong.message.YunKuaiChongEndChargeMessage;
import com.voltbridge.protocol.yunkuaichong.message.YunKuaiChongFrame;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 将云快充 0x19 协议消息转换为平台标准 {@link ChargingEndedEvent}。
 */
@Component
public class YunKuaiChongEndChargeHandler implements YunKuaiChongCommandHandler {
    private final YunKuaiChongEndChargeParser parser;
    private final TimeProvider timeProvider;

    public YunKuaiChongEndChargeHandler(YunKuaiChongEndChargeParser parser, TimeProvider timeProvider) {
        this.parser = parser;
        this.timeProvider = timeProvider;
    }

    @Override public int command() { return YunKuaiChongEndChargeParser.END_CHARGE_COMMAND; }

    @Override
    public ProtocolInboundResult handle(ProtocolContext context, YunKuaiChongFrame frame) {
        YunKuaiChongEndChargeMessage message = parser.parse(frame);
        // 登录会话设备与报文设备必须一致，防止连接串线后污染其他设备交易。
        DeviceId deviceId = requireDevice(context, message.deviceCode());
        String details = "{\"minimumCellVoltage\":" + message.minimumCellVoltage()
                + ",\"maximumCellVoltage\":" + message.maximumCellVoltage()
                + ",\"minimumTemperature\":" + message.minimumTemperature()
                + ",\"maximumTemperature\":" + message.maximumTemperature()
                + ",\"chargerCode\":\"" + message.chargerCode() + "\"}";
        return ProtocolInboundResult.events(java.util.List.of(new ChargingEndedEvent(UUID.randomUUID(),
                deviceId, context.protocol(), message.connectorNo(), message.transactionId(),
                message.endSoc(), message.minimumCellVoltage(), message.maximumCellVoltage(),
                message.minimumTemperature(), message.maximumTemperature(), message.chargingDuration(),
                message.outputEnergyKwh(), message.chargerCode(), details, timeProvider.now())));
    }

    private DeviceId requireDevice(ProtocolContext context, String payloadDeviceCode) {
        DeviceId deviceId = context.deviceId().orElseThrow(() -> new ProtocolException(
                "YKC_END_NOT_AUTHENTICATED", "End charge received before login"));
        if (!deviceId.value().equals(payloadDeviceCode)) {
            throw new ProtocolException("YKC_END_DEVICE_MISMATCH", "End charge device code mismatch");
        }
        return deviceId;
    }
}
