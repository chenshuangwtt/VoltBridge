package com.voltbridge.protocol.yunkuaichong.codec;

import com.voltbridge.device.command.StopChargingCommand;
import com.voltbridge.device.model.DeviceId;
import com.voltbridge.protocol.common.ProtocolContext;
import com.voltbridge.protocol.common.ProtocolException;
import com.voltbridge.protocol.common.codec.BcdCodec;
import com.voltbridge.protocol.yunkuaichong.command.YunKuaiChongCommandEncoder;
import com.voltbridge.protocol.yunkuaichong.command.YunKuaiChongPendingCommandRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** 将标准停充命令编码为云快充 V1.5 的 0x36 下行帧。 */
@Component
public class YunKuaiChongRemoteStopEncoder implements YunKuaiChongCommandEncoder<StopChargingCommand> {

    public static final int REMOTE_STOP_COMMAND = 0x36;
    public static final String COMMAND_TYPE = "STOP_CHARGING";
    private static final Logger LOGGER = LoggerFactory.getLogger(YunKuaiChongRemoteStopEncoder.class);

    private final YunKuaiChongFrameCodec frameCodec;
    private final YunKuaiChongPendingCommandRegistry pendingCommands;

    public YunKuaiChongRemoteStopEncoder(YunKuaiChongFrameCodec frameCodec,
                                         YunKuaiChongPendingCommandRegistry pendingCommands) {
        this.frameCodec = frameCodec;
        this.pendingCommands = pendingCommands;
    }

    @Override
    public Class<StopChargingCommand> commandType() {
        return StopChargingCommand.class;
    }

    @Override
    public byte[] encode(ProtocolContext context, StopChargingCommand command) {
        DeviceId sessionDevice = context.deviceId().orElseThrow(() ->
                new ProtocolException("YKC_SESSION_NOT_BOUND", "Remote stop requires an authenticated session"));
        if (!sessionDevice.equals(command.deviceId())) {
            throw new ProtocolException("YKC_DEVICE_MISMATCH", "Remote stop device does not match session");
        }

        // 0x36 消息体固定为 7 字节桩号 BCD + 1 字节枪号 BCD。
        byte[] payload = new byte[8];
        byte[] deviceCode = BcdCodec.encode(command.deviceId().value());
        System.arraycopy(deviceCode, 0, payload, 0, deviceCode.length);
        payload[7] = BcdCodec.encodeByte(Integer.parseInt(command.connectorNo()));

        byte[] frame = frameCodec.encode(context.nextSequence(), 0, REMOTE_STOP_COMMAND, payload);
        String correlationId = correlationId(command.deviceId().value(), command.connectorNo());
        pendingCommands.registerLatest(COMMAND_TYPE, correlationId, command.deviceId().value(),
                command.connectorNo(), command.commandId());
        LOGGER.info("YunKuaiChong 0x36 encoded: commandId={}, transactionId={}, tradeNo={}, deviceCode={}, connectorNo={}",
                command.commandId(), command.transactionId(), command.protocolTransactionId(),
                command.deviceId().value(), command.connectorNo());
        return frame;
    }

    public static String correlationId(String deviceCode, String connectorNo) {
        return deviceCode + ":" + connectorNo;
    }
}
