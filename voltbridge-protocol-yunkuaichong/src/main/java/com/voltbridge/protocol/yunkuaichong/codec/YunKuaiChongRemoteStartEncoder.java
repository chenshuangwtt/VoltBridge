package com.voltbridge.protocol.yunkuaichong.codec;

import com.voltbridge.device.command.StartChargingCommand;
import com.voltbridge.protocol.common.ProtocolContext;
import com.voltbridge.protocol.common.codec.BcdCodec;
import com.voltbridge.protocol.yunkuaichong.command.YunKuaiChongCommandEncoder;
import com.voltbridge.protocol.yunkuaichong.command.YunKuaiChongPendingCommandRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

@Component
public class YunKuaiChongRemoteStartEncoder implements YunKuaiChongCommandEncoder<StartChargingCommand> {

    public static final int REMOTE_START_COMMAND = 0x34;
    private static final Logger LOGGER = LoggerFactory.getLogger(YunKuaiChongRemoteStartEncoder.class);
    private static final BigInteger TRADE_NO_MODULUS = BigInteger.TEN.pow(32);

    private final YunKuaiChongFrameCodec frameCodec;
    private final YunKuaiChongPendingCommandRegistry pendingCommands;

    public YunKuaiChongRemoteStartEncoder(YunKuaiChongFrameCodec frameCodec,
                                          YunKuaiChongPendingCommandRegistry pendingCommands) {
        this.frameCodec = frameCodec;
        this.pendingCommands = pendingCommands;
    }

    @Override
    public Class<StartChargingCommand> commandType() {
        return StartChargingCommand.class;
    }

    @Override
    public byte[] encode(ProtocolContext context, StartChargingCommand command) {
        String deviceCode = command.deviceId().value();
        String correlationId = correlationId(command.commandId());
        byte[] payload = new byte[44];
        copyBcd(payload, 0, correlationId, 32);
        copyBcd(payload, 16, deviceCode, 14);
        copyBcd(payload, 23, leftPad(command.connectorNo(), 2), 2);
        String logicalCardNo = command.logicalCardNo() == null
                ? correlationId.substring(correlationId.length() - 16) : command.logicalCardNo();
        copyBcd(payload, 24, leftPad(logicalCardNo, 16), 16);
        long physicalCardNo = command.physicalCardNo() == null ? 0L : Long.parseLong(command.physicalCardNo());
        int limitCents = command.limitAmount().movePointRight(2).intValueExact();
        ByteBuffer.wrap(payload, 32, 12).order(ByteOrder.LITTLE_ENDIAN)
                .putLong(physicalCardNo)
                .putInt(limitCents);

        byte[] frame = frameCodec.encode(context.nextSequence(), 0, REMOTE_START_COMMAND, payload);
        pendingCommands.register(correlationId, deviceCode, command.connectorNo(), command.commandId());
        LOGGER.info("YunKuaiChong 0x34 encoded: commandId={}, deviceCode={}, connectorNo={}, correlationId={}",
                command.commandId(), deviceCode, command.connectorNo(), correlationId);
        return frame;
    }

    public static String correlationId(java.util.UUID commandId) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(commandId.toString().getBytes(StandardCharsets.US_ASCII));
            String value = new BigInteger(1, digest).mod(TRADE_NO_MODULUS).toString();
            return leftPad(value, 32);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void copyBcd(byte[] target, int offset, String value, int digits) {
        byte[] encoded = BcdCodec.encode(leftPad(value, digits));
        System.arraycopy(encoded, 0, target, offset, encoded.length);
    }

    private static String leftPad(String value, int length) {
        if (value == null || value.length() > length || !value.matches("\\d+")) {
            throw new IllegalArgumentException("YunKuaiChong numeric field exceeds " + length + " digits");
        }
        return "0".repeat(length - value.length()) + value;
    }
}
