package com.voltbridge.testing.simulator.scenario;

import java.util.UUID;

/** 场景编排器访问平台公开业务边界所需的最小操作集合。 */
public interface PlatformScenarioControl {
    UUID requestRemoteStart();
    UUID requestRemoteStop();
    void awaitStartAcknowledged(UUID commandId) throws Exception;
    void awaitStartSucceeded(UUID commandId) throws Exception;
    void awaitTransactionCharging() throws Exception;
    void awaitStopAcknowledged(UUID commandId) throws Exception;
    void awaitStopSucceeded(UUID commandId) throws Exception;
    void awaitTransactionCompleted() throws Exception;
    UUID chargingTransactionId();
}
