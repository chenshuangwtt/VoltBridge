package com.voltbridge.business.domain.command;

import java.time.Instant;
import java.util.UUID;

/**
 * 可持久化的业务命令聚合，负责维护设备命令从创建到最终结果的生命周期。
 *
 * <p>该对象由业务领域拥有并持久化；device-api 中的同名接口只是跨网关边界的下行消息。
 * 本聚合不保存 sessionId、协议 uniqueId 或 Netty 对象。状态语义在所有协议中一致：</p>
 * <ul>
 *   <li>{@code SENT}：传输写出成功；</li>
 *   <li>{@code ACKED}：设备/协议接受命令；</li>
 *   <li>{@code SUCCEEDED}：观察到真实业务效果。</li>
 * </ul>
 */
public class DeviceCommand {

    private final UUID id;
    private final UUID tenantId;
    private final UUID deviceId;
    private final String deviceCode;
    private final UUID connectorId;
    private final String connectorNo;
    private final DeviceCommandType type;
    private final String businessRef;
    private final String requestPayload;
    private final Instant createdAt;
    private Instant updatedAt;
    private long version;
    private DeviceCommandStatus status;
    private String responsePayload;
    private Instant sentAt;
    private Instant ackedAt;
    private Instant completedAt;
    private Instant expiredAt;
    private String failureCode;
    private String failureReason;

    private DeviceCommand(UUID id, UUID tenantId, UUID deviceId, String deviceCode,
                          UUID connectorId, String connectorNo,
                          DeviceCommandType type, DeviceCommandStatus status, String businessRef,
                          String requestPayload, String responsePayload, Instant createdAt,
                          Instant updatedAt, Instant sentAt, Instant ackedAt, Instant completedAt,
                          Instant expiredAt, String failureCode, String failureReason, long version) {
        this.id = id;
        this.tenantId = tenantId;
        this.deviceId = deviceId;
        this.deviceCode = deviceCode;
        this.connectorId = connectorId;
        this.connectorNo = connectorNo;
        this.type = type;
        this.status = status;
        this.businessRef = businessRef;
        this.requestPayload = requestPayload;
        this.responsePayload = responsePayload;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.sentAt = sentAt;
        this.ackedAt = ackedAt;
        this.completedAt = completedAt;
        this.expiredAt = expiredAt;
        this.failureCode = failureCode;
        this.failureReason = failureReason;
        this.version = version;
    }

    public static DeviceCommand create(UUID tenantId, UUID deviceId, UUID connectorId,
                                       DeviceCommandType type, String businessRef,
                                       String requestPayload, Instant now, Instant expiresAt) {
        return create(tenantId, deviceId, null, connectorId, null, type,
                businessRef, requestPayload, now, expiresAt);
    }

    public static DeviceCommand create(UUID tenantId, UUID deviceId, String deviceCode,
                                       UUID connectorId, String connectorNo,
                                       DeviceCommandType type, String businessRef,
                                       String requestPayload, Instant now, Instant expiresAt) {
        UUID commandId = UUID.randomUUID();
        String effectiveBusinessRef = businessRef == null || businessRef.isBlank()
                ? commandId.toString() : businessRef;
        return new DeviceCommand(commandId, tenantId, deviceId, deviceCode, connectorId, connectorNo, type,
                DeviceCommandStatus.CREATED, effectiveBusinessRef, requestPayload, null, now,
                now, null, null, null, expiresAt, null, null, 0);
    }

    public static DeviceCommand restore(UUID id, UUID tenantId, UUID deviceId, String deviceCode,
                                        UUID connectorId, String connectorNo,
                                        DeviceCommandType type, DeviceCommandStatus status,
                                        String businessRef, String requestPayload, String responsePayload,
                                        Instant createdAt, Instant updatedAt, Instant sentAt, Instant ackedAt,
                                        Instant completedAt, Instant expiredAt,
                                        String failureCode, String failureReason, long version) {
        return new DeviceCommand(id, tenantId, deviceId, deviceCode, connectorId, connectorNo,
                type, status, businessRef,
                requestPayload, responsePayload, createdAt, updatedAt, sentAt, ackedAt, completedAt,
                expiredAt, failureCode, failureReason, version);
    }

    public synchronized boolean markSent(Instant now) {
        // 只有传输层完成写出才能从 CREATED 进入 SENT，设备此时可能尚未处理。
        if (status != DeviceCommandStatus.CREATED) {
            return false;
        }
        status = DeviceCommandStatus.SENT;
        sentAt = now;
        updatedAt = now;
        return true;
    }

    public synchronized boolean markAcked(String response, Instant now) {
        // ACKED 记录设备接受控制请求，不等价于车辆已开始或停止充电。
        if (status != DeviceCommandStatus.SENT) {
            return false;
        }
        status = DeviceCommandStatus.ACKED;
        responsePayload = response;
        ackedAt = now;
        updatedAt = now;
        return true;
    }

    public synchronized boolean markExecuting(Instant now) {
        // 仅协议明确提供执行中语义时使用；没有该阶段的协议可直接等待业务效果。
        if (status != DeviceCommandStatus.ACKED) {
            return false;
        }
        status = DeviceCommandStatus.EXECUTING;
        updatedAt = now;
        return true;
    }

    public synchronized boolean markSucceeded(String response, Instant now) {
        // SUCCEEDED 必须由充电开始/结束等真实效果证据触发，不能由网络 ACK 直接触发。
        if (status != DeviceCommandStatus.ACKED && status != DeviceCommandStatus.EXECUTING) {
            return false;
        }
        status = DeviceCommandStatus.SUCCEEDED;
        responsePayload = response;
        completedAt = now;
        updatedAt = now;
        return true;
    }

    public synchronized boolean markFailed(String code, String reason, String response, Instant now) {
        // 终态不可被迟到报文覆盖，保证命令审计历史稳定。
        if (isTerminal()) {
            return false;
        }
        status = DeviceCommandStatus.FAILED;
        failureCode = code;
        failureReason = reason;
        responsePayload = response;
        completedAt = now;
        updatedAt = now;
        return true;
    }

    public synchronized boolean markAcknowledgementFailed(String code, String reason,
                                                            String response, Instant now) {
        // 明确拒绝也是一种 ACK，因此同时记录 ackedAt 和失败终态。
        if (status != DeviceCommandStatus.SENT) {
            return false;
        }
        status = DeviceCommandStatus.FAILED;
        failureCode = code;
        failureReason = reason;
        responsePayload = response;
        ackedAt = now;
        completedAt = now;
        updatedAt = now;
        return true;
    }

    public synchronized boolean markTimedOut(Instant now) {
        // 超时只终结命令等待，不推断设备上的真实充电状态。
        if (status != DeviceCommandStatus.SENT) {
            return false;
        }
        status = DeviceCommandStatus.TIMEOUT;
        completedAt = now;
        updatedAt = now;
        return true;
    }

    private boolean isTerminal() {
        return status == DeviceCommandStatus.SUCCEEDED
                || status == DeviceCommandStatus.FAILED
                || status == DeviceCommandStatus.TIMEOUT;
    }

    public UUID id() { return id; }
    public UUID tenantId() { return tenantId; }
    public UUID deviceId() { return deviceId; }
    public String deviceCode() { return deviceCode; }
    public UUID connectorId() { return connectorId; }
    public String connectorNo() { return connectorNo; }
    public DeviceCommandType type() { return type; }
    public DeviceCommandStatus status() { return status; }
    public String businessRef() { return businessRef; }
    public String requestPayload() { return requestPayload; }
    public String responsePayload() { return responsePayload; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }
    public Instant sentAt() { return sentAt; }
    public Instant ackedAt() { return ackedAt; }
    public Instant completedAt() { return completedAt; }
    public Instant expiredAt() { return expiredAt; }
    public String failureCode() { return failureCode; }
    public String failureReason() { return failureReason; }
    public long version() { return version; }
}
