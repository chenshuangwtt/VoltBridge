package com.voltbridge.protocol.yunkuaichong;

import com.voltbridge.protocol.common.ProtocolException;
import com.voltbridge.protocol.common.codec.HexCodec;
import com.voltbridge.protocol.yunkuaichong.codec.YunKuaiChongChecksum;
import com.voltbridge.protocol.yunkuaichong.codec.YunKuaiChongFrameCodec;
import com.voltbridge.protocol.yunkuaichong.codec.YunKuaiChongLoginAckEncoder;
import com.voltbridge.protocol.yunkuaichong.codec.YunKuaiChongLoginParser;
import com.voltbridge.protocol.yunkuaichong.message.YunKuaiChongFrame;
import com.voltbridge.protocol.yunkuaichong.message.YunKuaiChongLoginMessage;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class YunKuaiChongLoginProtocolTest {

    private static final String LOGIN_FRAME =
            "6822001900012023121200001001011047562E393572313300898604D11722D0348606024E87";
    private static final String SUCCESS_ACK = "680C00190002202312120000100055A1";

    private final YunKuaiChongFrameCodec frameCodec = new YunKuaiChongFrameCodec();

    @Test
    void decodesKnownLoginFrame() {
        YunKuaiChongFrame frame = frameCodec.decode(HexCodec.decode(LOGIN_FRAME));

        assertThat(frame.sequenceNumber()).isEqualTo(0x0019);
        assertThat(frame.encryptionFlag()).isZero();
        assertThat(frame.command()).isEqualTo(0x01);
        assertThat(frame.payload()).hasSize(30);
        assertThat(frame.checksum()).isEqualTo(0x4E87);
    }

    @Test
    void validatesCrcInObservedWireOrderOnly() {
        byte[] rawFrame = HexCodec.decode(LOGIN_FRAME);

        assertThat(YunKuaiChongChecksum.crc16Modbus(rawFrame, 2, 34)).isEqualTo(0x4E87);
        assertThat(rawFrame[36] & 0xFF).isEqualTo(0x4E);
        assertThat(rawFrame[37] & 0xFF).isEqualTo(0x87);

        byte[] reversedChecksum = rawFrame.clone();
        reversedChecksum[36] = (byte) 0x87;
        reversedChecksum[37] = (byte) 0x4E;
        assertThatThrownBy(() -> frameCodec.decode(reversedChecksum))
                .isInstanceOf(ProtocolException.class)
                .hasMessageContaining("CRC mismatch");
    }

    @Test
    void parsesLoginFieldsWithoutHardCodingDeviceCode() {
        YunKuaiChongFrame frame = frameCodec.decode(HexCodec.decode(LOGIN_FRAME));
        YunKuaiChongLoginMessage login = new YunKuaiChongLoginParser().parse(frame);

        assertThat(login.deviceCode()).isEqualTo("20231212000010");
        assertThat(login.pileType()).isEqualTo(1);
        assertThat(login.connectorCount()).isEqualTo(1);
        assertThat(login.communicationProtocolVersion()).isEqualTo(0x10);
        assertThat(login.programVersion()).isEqualTo("GV.95r13");
        assertThat(login.networkType()).isZero();
        assertThat(login.simCard()).isEqualTo("898604D11722D0348606");
        assertThat(login.operatorCode()).isEqualTo(2);
    }

    @Test
    void encodesKnownLoginSuccessAck() {
        YunKuaiChongFrame request = frameCodec.decode(HexCodec.decode(LOGIN_FRAME));
        YunKuaiChongLoginAckEncoder encoder = new YunKuaiChongLoginAckEncoder(frameCodec);

        byte[] ack = encoder.encode(request, "20231212000010", true);

        assertThat(HexCodec.encode(ack)).isEqualTo(SUCCESS_ACK);
        assertThat(frameCodec.decode(ack).command()).isEqualTo(0x02);
    }
}
