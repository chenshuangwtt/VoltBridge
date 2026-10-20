package com.voltbridge.infra.cache;

import com.voltbridge.device.model.DeviceId;
import com.voltbridge.device.model.DeviceProtocol;
import com.voltbridge.gateway.routing.DeviceRoute;
import com.voltbridge.gateway.session.SessionId;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class CaffeineDeviceRouteDirectoryTest {

    @Test
    void replacesRouteWithNewerLoginAndProtectsItFromOldDisconnect() {
        CaffeineDeviceRouteDirectory directory = new CaffeineDeviceRouteDirectory();
        DeviceId deviceId = new DeviceId("20231212000010");
        Instant firstLoginAt = Instant.parse("2026-10-18T01:00:00Z");
        DeviceRoute firstRoute = route(deviceId, "session-1", 1, firstLoginAt);
        DeviceRoute secondRoute = route(deviceId, "session-2", 2, firstLoginAt.plusSeconds(1));

        assertThat(directory.claim(firstRoute)).isNull();
        assertThat(directory.claim(secondRoute)).isEqualTo(firstRoute);

        assertThat(directory.find(deviceId)).contains(secondRoute);
        assertThat(directory.remove(deviceId, firstRoute.sessionId(), firstRoute.connectionEpoch())).isFalse();
        assertThat(directory.find(deviceId)).contains(secondRoute);
        assertThat(directory.remove(deviceId, secondRoute.sessionId(), secondRoute.connectionEpoch())).isTrue();
        assertThat(directory.find(deviceId)).isEmpty();
    }

    private DeviceRoute route(DeviceId deviceId, String sessionId, long epoch, Instant authenticatedAt) {
        return new DeviceRoute(deviceId, "test-node", new SessionId(sessionId), epoch,
                DeviceProtocol.YUN_KUAI_CHONG_150, authenticatedAt, authenticatedAt,
                authenticatedAt.plus(Duration.ofMinutes(10)));
    }
}
