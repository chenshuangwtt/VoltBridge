package com.voltbridge.gateway.session;

import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 单体模式下基于并发 Map 的本地会话注册表。
 */
@Component
public class LocalDeviceSessionRegistry implements DeviceSessionRegistry {

    private final ConcurrentMap<SessionId, DeviceSession> sessions = new ConcurrentHashMap<>();

    @Override
    public void register(DeviceSession session) {
        DeviceSession existing = sessions.putIfAbsent(session.sessionId(), session);
        if (existing != null) {
            throw new IllegalStateException("Session already registered: " + session.sessionId().value());
        }
    }

    @Override
    public Optional<DeviceSession> find(SessionId sessionId) {
        return Optional.ofNullable(sessions.get(sessionId));
    }

    @Override
    public Optional<DeviceSession> remove(SessionId sessionId) {
        return Optional.ofNullable(sessions.remove(sessionId));
    }

    @Override
    public List<DeviceSession> sessions() {
        return List.copyOf(sessions.values());
    }

    @Override
    public int size() {
        return sessions.size();
    }
}
