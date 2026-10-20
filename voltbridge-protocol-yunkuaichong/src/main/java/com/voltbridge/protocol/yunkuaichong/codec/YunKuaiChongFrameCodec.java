package com.voltbridge.protocol.yunkuaichong.codec;

import com.voltbridge.protocol.common.ProtocolException;
import com.voltbridge.protocol.yunkuaichong.message.YunKuaiChongFrame;
import org.springframework.stereotype.Component;

import java.util.Arrays;

/**
 * 云快充 V1.5 帧编解码器。
 *
 * <p>CRC 覆盖序列号至消息体，并严格按已知 V1.5 样例的高字节在前顺序写入。</p>
 */
@Component
public class YunKuaiChongFrameCodec {

    public static final int HEADER = 0x68;
    private static final int FIXED_DATA_LENGTH = 4;
    private static final int MIN_FRAME_LENGTH = 8;

    public YunKuaiChongFrame decode(byte[] rawFrame) {
        if (rawFrame == null || rawFrame.length < MIN_FRAME_LENGTH) {
            throw new ProtocolException("YKC_FRAME_TOO_SHORT", "YunKuaiChong frame must contain at least 8 bytes");
        }
        if ((rawFrame[0] & 0xFF) != HEADER) {
            throw new ProtocolException("YKC_INVALID_HEADER", "YunKuaiChong frame header must be 0x68");
        }
        int dataLength = rawFrame[1] & 0xFF;
        if (dataLength < FIXED_DATA_LENGTH) {
            throw new ProtocolException("YKC_INVALID_LENGTH", "YunKuaiChong data length must be at least 4");
        }
        int expectedFrameLength = dataLength + 4;
        if (rawFrame.length != expectedFrameLength) {
            throw new ProtocolException("YKC_LENGTH_MISMATCH",
                    "Expected frame length " + expectedFrameLength + " but received " + rawFrame.length);
        }
        int checksumOffset = 2 + dataLength;
        int transmittedChecksum = ((rawFrame[checksumOffset] & 0xFF) << 8)
                | (rawFrame[checksumOffset + 1] & 0xFF);
        int calculatedChecksum = YunKuaiChongChecksum.crc16Modbus(rawFrame, 2, dataLength);
        if (transmittedChecksum != calculatedChecksum) {
            throw new ProtocolException("YKC_CHECKSUM_MISMATCH",
                    "YunKuaiChong CRC mismatch: expected 0x" + Integer.toHexString(calculatedChecksum)
                            + " but received 0x" + Integer.toHexString(transmittedChecksum));
        }
        int sequenceNumber = ((rawFrame[2] & 0xFF) << 8) | (rawFrame[3] & 0xFF);
        int encryptionFlag = rawFrame[4] & 0xFF;
        int command = rawFrame[5] & 0xFF;
        byte[] payload = Arrays.copyOfRange(rawFrame, 6, checksumOffset);
        return new YunKuaiChongFrame(sequenceNumber, encryptionFlag, command,
                payload, calculatedChecksum, rawFrame);
    }

    public byte[] encode(int sequenceNumber, int encryptionFlag, int command, byte[] payload) {
        if (sequenceNumber < 0 || sequenceNumber > 0xFFFF) {
            throw new IllegalArgumentException("sequence number must fit an unsigned short");
        }
        if (encryptionFlag < 0 || encryptionFlag > 0xFF || command < 0 || command > 0xFF) {
            throw new IllegalArgumentException("encryption flag and command must fit an unsigned byte");
        }
        byte[] body = payload == null ? new byte[0] : Arrays.copyOf(payload, payload.length);
        int dataLength = FIXED_DATA_LENGTH + body.length;
        if (dataLength > 0xFF) {
            throw new IllegalArgumentException("YunKuaiChong data area exceeds one-byte length field");
        }
        byte[] frame = new byte[dataLength + 4];
        frame[0] = (byte) HEADER;
        frame[1] = (byte) dataLength;
        frame[2] = (byte) (sequenceNumber >>> 8);
        frame[3] = (byte) sequenceNumber;
        frame[4] = (byte) encryptionFlag;
        frame[5] = (byte) command;
        System.arraycopy(body, 0, frame, 6, body.length);
        int checksum = YunKuaiChongChecksum.crc16Modbus(frame, 2, dataLength);
        int checksumOffset = 2 + dataLength;
        frame[checksumOffset] = (byte) (checksum >>> 8);
        frame[checksumOffset + 1] = (byte) checksum;
        return frame;
    }
}
