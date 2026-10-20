package com.voltbridge.transport.tcp;

import io.netty.channel.ChannelHandler;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;

import java.nio.ByteOrder;

final class TcpFrameDecoderFactory {

    private TcpFrameDecoderFactory() {
    }

    static ChannelHandler create(TcpServerProperties properties) {
        TcpServerProperties.Framing framing = properties.getFraming();
        ByteOrder byteOrder = framing.getByteOrder() == TcpServerProperties.ByteOrder.LITTLE_ENDIAN
                ? java.nio.ByteOrder.LITTLE_ENDIAN
                : java.nio.ByteOrder.BIG_ENDIAN;
        return new LengthFieldBasedFrameDecoder(byteOrder, framing.getMaxFrameLength(),
                framing.getLengthFieldOffset(), framing.getLengthFieldLength(),
                framing.getLengthAdjustment(), framing.getInitialBytesToStrip(), framing.isFailFast());
    }
}
