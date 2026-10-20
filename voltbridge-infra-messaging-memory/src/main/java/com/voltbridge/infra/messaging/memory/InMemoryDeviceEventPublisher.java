package com.voltbridge.infra.messaging.memory;

import com.voltbridge.device.event.DeviceEvent;
import com.voltbridge.device.port.DeviceEventHandler;
import com.voltbridge.device.port.DeviceEventPublisher;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 当前单体进程内的类型化同步事件分发器。
 *
 * <p>启动时把 Spring 收集的 {@link DeviceEventHandler} 按事件 Class 建立只读注册表；发布时
 * 不使用不断扩张的 {@code instanceof} 分支。同步语义也保证协议成功应答可等待关键业务处理完成。</p>
 */
@Component
public class InMemoryDeviceEventPublisher implements DeviceEventPublisher {

    private final Map<Class<? extends DeviceEvent>, List<DeviceEventHandler<? extends DeviceEvent>>> handlers;

    public InMemoryDeviceEventPublisher(List<DeviceEventHandler<? extends DeviceEvent>> handlers) {
        // 处理器类型由各业务用例声明；重复事件类型允许存在多个独立处理器。
        Map<Class<? extends DeviceEvent>, List<DeviceEventHandler<? extends DeviceEvent>>> registered = new HashMap<>();
        handlers.forEach(handler -> registered.computeIfAbsent(handler.eventType(), ignored -> new ArrayList<>())
                .add(handler));
        registered.replaceAll((ignored, matchingHandlers) -> List.copyOf(matchingHandlers));
        this.handlers = Map.copyOf(registered);
    }

    @Override
    public void publish(DeviceEvent event) {
        // 精确类型处理器负责业务，DeviceEvent 通配处理器只用于审计等横切场景。
        dispatch(handlers.getOrDefault(event.getClass(), List.of()), event);
        dispatch(handlers.getOrDefault(DeviceEvent.class, List.of()), event);
    }

    @SuppressWarnings("unchecked")
    private <E extends DeviceEvent> void dispatch(List<DeviceEventHandler<? extends DeviceEvent>> matchingHandlers,
                                                  E event) {
        matchingHandlers.forEach(handler -> ((DeviceEventHandler<E>) handler).handle(event));
    }
}
