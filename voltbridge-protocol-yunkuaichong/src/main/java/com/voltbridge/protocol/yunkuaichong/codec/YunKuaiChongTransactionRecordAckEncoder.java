package com.voltbridge.protocol.yunkuaichong.codec;

import com.voltbridge.protocol.common.codec.BcdCodec;
import com.voltbridge.protocol.yunkuaichong.message.YunKuaiChongFrame;
import org.springframework.stereotype.Component;

/**
 * 编码云快充 V1.5 0x40 交易记录应答。
 *
 * <p>应答复用请求序列号和加密标志，消息体为 16 字节流水号与 1 字节处理结果。</p>
 */
@Component
public class YunKuaiChongTransactionRecordAckEncoder {
    public static final int TRANSACTION_RECORD_ACK_COMMAND = 0x40;
    private final YunKuaiChongFrameCodec frameCodec;

    public YunKuaiChongTransactionRecordAckEncoder(YunKuaiChongFrameCodec frameCodec) {
        this.frameCodec = frameCodec;
    }

    public byte[] encode(YunKuaiChongFrame request, String transactionId, boolean accepted) {
        // tradeNo 按协议固定为 32 位 BCD；兼容较短业务流水时在左侧补零。
        String padded = "0".repeat(32 - transactionId.length()) + transactionId;
        byte[] payload = new byte[17];
        System.arraycopy(BcdCodec.encode(padded), 0, payload, 0, 16);
        payload[16] = accepted ? (byte) 0x00 : (byte) 0x01;
        return frameCodec.encode(request.sequenceNumber(), request.encryptionFlag(),
                TRANSACTION_RECORD_ACK_COMMAND, payload);
    }
}
