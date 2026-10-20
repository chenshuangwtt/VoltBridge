package com.voltbridge.protocol.yunkuaichong.command;

import com.voltbridge.common.time.TimeProvider;
import com.voltbridge.device.event.CommandResultEvent;
import com.voltbridge.device.event.CommandResultStatus;
import com.voltbridge.device.model.DeviceId;
import com.voltbridge.protocol.common.ProtocolContext;
import com.voltbridge.protocol.common.ProtocolException;
import com.voltbridge.protocol.common.codec.BcdCodec;
import com.voltbridge.protocol.common.spi.ProtocolInboundResult;
import com.voltbridge.protocol.yunkuaichong.message.YunKuaiChongFrame;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.UUID;

/** 云快充 0x91 重启结果；0x91 才是成功依据，随后连接断开是预期副作用而非失败。 */
@Component
public class YunKuaiChongResetResultHandler implements YunKuaiChongCommandHandler {
    private final YunKuaiChongPendingCommandRegistry pending; private final TimeProvider time;
    public YunKuaiChongResetResultHandler(YunKuaiChongPendingCommandRegistry pending, TimeProvider time){this.pending=pending;this.time=time;}
    @Override public int command(){return 0x91;}
    @Override public ProtocolInboundResult handle(ProtocolContext context,YunKuaiChongFrame frame){
        if(frame.payload().length!=8) throw new ProtocolException("YKC_RESET_RESULT_LENGTH","0x91 payload must contain 8 bytes");
        String deviceCode=BcdCodec.decode(java.util.Arrays.copyOfRange(frame.payload(),0,7));
        DeviceId device=context.deviceId().orElseThrow(()->new ProtocolException("YKC_RESET_NOT_AUTHENTICATED","0x91 before login"));
        if(!device.value().equals(deviceCode)) throw new ProtocolException("YKC_RESET_DEVICE_MISMATCH","0x91 device mismatch");
        var resolved=pending.resolve("RESET_DEVICE", "", deviceCode,"0").orElse(null);
        if(resolved==null) return ProtocolInboundResult.empty();
        boolean success=(frame.payload()[7]&0xff)==1;
        return ProtocolInboundResult.events(List.of(new CommandResultEvent(UUID.randomUUID(),device,resolved.commandId(),"",null,"0","RESET_DEVICE",success?CommandResultStatus.SUCCEEDED:CommandResultStatus.FAILED,success?null:"DEVICE_REJECTED",success?null:"Device rejected reset",frame.payload()[7]&0xff,null,resolved.duplicate(),time.now())));
    }
}
