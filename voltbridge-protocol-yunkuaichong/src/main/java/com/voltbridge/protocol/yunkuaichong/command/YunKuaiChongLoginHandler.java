package com.voltbridge.protocol.yunkuaichong.command;

import com.voltbridge.common.time.TimeProvider;
import com.voltbridge.device.event.DeviceConnectedEvent;
import com.voltbridge.device.model.DeviceId;
import com.voltbridge.protocol.common.ProtocolContext;
import com.voltbridge.protocol.common.spi.ProtocolAuthenticationRequest;
import com.voltbridge.protocol.common.spi.ProtocolInboundResult;
import com.voltbridge.protocol.yunkuaichong.codec.YunKuaiChongLoginAckEncoder;
import com.voltbridge.protocol.yunkuaichong.codec.YunKuaiChongLoginParser;
import com.voltbridge.protocol.yunkuaichong.message.YunKuaiChongFrame;
import com.voltbridge.protocol.yunkuaichong.message.YunKuaiChongLoginMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import com.voltbridge.transport.api.BinaryTransportPayload;

import java.util.UUID;

/**
 * 把云快充 0x01 登录帧转换为统一设备认证请求，并延迟生成 0x02 应答。
 *
 * <p>设备是否允许接入由网关调用业务认证器决定；协议层只解析身份并根据最终决策编码 ACK。</p>
 */
@Component
public class YunKuaiChongLoginHandler implements YunKuaiChongCommandHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(YunKuaiChongLoginHandler.class);
    private final YunKuaiChongLoginParser parser;
    private final YunKuaiChongLoginAckEncoder ackEncoder;
    private final TimeProvider timeProvider;

    public YunKuaiChongLoginHandler(YunKuaiChongLoginParser parser,
                                    YunKuaiChongLoginAckEncoder ackEncoder,
                                    TimeProvider timeProvider) {
        this.parser = parser;
        this.ackEncoder = ackEncoder;
        this.timeProvider = timeProvider;
    }

    @Override
    public int command() {
        return YunKuaiChongLoginParser.LOGIN_COMMAND;
    }

    @Override
    public ProtocolInboundResult handle(ProtocolContext context, YunKuaiChongFrame frame) {
        // RAW frame -> protocol login DTO。
        YunKuaiChongLoginMessage login = parser.parse(frame);
        // protocol DTO -> canonical authentication event，业务层不会看到 0x01 字段布局。
        DeviceConnectedEvent event = new DeviceConnectedEvent(UUID.randomUUID(),
                new DeviceId(login.deviceCode()), context.protocol(), "1.5",
                context.sessionId(), context.remoteAddress(), timeProvider.now());
        LOGGER.info("YunKuaiChong login parsed: deviceCode={}, sessionId={}, connectors={}, programVersion={}",
                login.deviceCode(), context.sessionId(), login.connectorCount(), login.programVersion());
        // 0x02 在业务认证完成后才编码，防止未注册设备提前收到成功响应。
        ProtocolAuthenticationRequest request = new ProtocolAuthenticationRequest(event,
                accepted -> new BinaryTransportPayload(
                        ackEncoder.encode(frame, login.deviceCode(), accepted)));
        return ProtocolInboundResult.authentication(request);
    }
}
