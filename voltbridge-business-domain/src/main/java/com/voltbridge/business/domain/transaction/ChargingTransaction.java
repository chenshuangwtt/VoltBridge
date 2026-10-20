package com.voltbridge.business.domain.transaction;

import com.voltbridge.device.model.ChargingStartMethod;
import com.voltbridge.device.model.ChargingStopCategory;
import com.voltbridge.device.model.DeviceProtocol;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 一次真实充电会话的领域聚合根。
 *
 * <p>该聚合独立于设备命令、订单和支付，负责维护交易关联、状态机、计量结果及最终记录
 * 幂等性。协议层只提供标准事件，所有状态转换由本聚合集中约束。{@code commandId} 是远程
 * 启动命令关联，{@code id} 是平台交易标识，{@code protocolTransactionId} 是设备协议交易标识，
 * 三者不能混用。</p>
 */
public class ChargingTransaction {

    private final UUID id;
    private final UUID tenantId;
    private final UUID deviceId;
    private final String deviceCode;
    private final UUID connectorId;
    private final int connectorNo;
    private final DeviceProtocol protocol;
    private final UUID commandId;
    private final String businessRef;
    private final Instant createdAt;
    private String protocolTransactionId;
    private ChargingTransactionStatus status;
    private Instant startedAt;
    private Instant endedAt;
    private BigDecimal startMeterKwh;
    private BigDecimal endMeterKwh;
    private BigDecimal energyKwh;
    private Integer startSoc;
    private Integer latestSoc;
    private Integer endSoc;
    private Duration chargingDuration;
    private BigDecimal totalAmount;
    private ChargingStopCategory stopCategory;
    private Integer rawStopCode;
    private String rawStopDetail;
    private String vehicleVin;
    private ChargingStartMethod startMethod;
    private Instant lastProgressAt;
    private Instant finalRecordReceivedAt;
    private String rawFinalRecord;
    private String additionalInfo;
    private Instant updatedAt;
    private long version;

    private ChargingTransaction(UUID id, UUID tenantId, UUID deviceId, String deviceCode,
                                UUID connectorId, int connectorNo, DeviceProtocol protocol,
                                UUID commandId, String businessRef, String protocolTransactionId,
                                ChargingTransactionStatus status, Instant startedAt, Instant endedAt,
                                BigDecimal startMeterKwh, BigDecimal endMeterKwh, BigDecimal energyKwh,
                                Integer startSoc, Integer latestSoc, Integer endSoc,
                                Duration chargingDuration, BigDecimal totalAmount,
                                ChargingStopCategory stopCategory, Integer rawStopCode,
                                String rawStopDetail, String vehicleVin, ChargingStartMethod startMethod,
                                Instant lastProgressAt, Instant finalRecordReceivedAt,
                                String rawFinalRecord, String additionalInfo,
                                Instant createdAt, Instant updatedAt, long version) {
        this.id = Objects.requireNonNull(id);
        this.tenantId = Objects.requireNonNull(tenantId);
        this.deviceId = Objects.requireNonNull(deviceId);
        this.deviceCode = Objects.requireNonNull(deviceCode);
        this.connectorId = Objects.requireNonNull(connectorId);
        this.connectorNo = connectorNo;
        this.protocol = Objects.requireNonNull(protocol);
        this.commandId = commandId;
        this.businessRef = businessRef;
        this.protocolTransactionId = protocolTransactionId;
        this.status = Objects.requireNonNull(status);
        this.startedAt = startedAt;
        this.endedAt = endedAt;
        this.startMeterKwh = startMeterKwh;
        this.endMeterKwh = endMeterKwh;
        this.energyKwh = energyKwh;
        this.startSoc = startSoc;
        this.latestSoc = latestSoc;
        this.endSoc = endSoc;
        this.chargingDuration = chargingDuration;
        this.totalAmount = totalAmount;
        this.stopCategory = stopCategory;
        this.rawStopCode = rawStopCode;
        this.rawStopDetail = rawStopDetail;
        this.vehicleVin = vehicleVin;
        this.startMethod = startMethod;
        this.lastProgressAt = lastProgressAt;
        this.finalRecordReceivedAt = finalRecordReceivedAt;
        this.rawFinalRecord = rawFinalRecord;
        this.additionalInfo = additionalInfo;
        this.createdAt = Objects.requireNonNull(createdAt);
        this.updatedAt = Objects.requireNonNull(updatedAt);
        this.version = version;
    }

    public static ChargingTransaction start(UUID tenantId, UUID deviceId, String deviceCode,
                                            UUID connectorId, int connectorNo, DeviceProtocol protocol,
                                            UUID commandId, String businessRef, Instant now) {
        return new ChargingTransaction(UUID.randomUUID(), tenantId, deviceId, deviceCode,
                connectorId, connectorNo, protocol, commandId, businessRef, null,
                ChargingTransactionStatus.STARTING, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null,
                ChargingStartMethod.REMOTE, null, null, null, "{}", now, now, 0);
    }

    /** 创建没有平台远程命令的设备主动交易。 */
    public static ChargingTransaction start(UUID tenantId, UUID deviceId, String deviceCode,
                                            UUID connectorId, int connectorNo, DeviceProtocol protocol,
                                            ChargingStartMethod startMethod, Instant now) {
        return new ChargingTransaction(UUID.randomUUID(), tenantId, deviceId, deviceCode,
                connectorId, connectorNo, protocol, null, null, null,
                ChargingTransactionStatus.STARTING, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null,
                startMethod, null, null, null, "{}", now, now, 0);
    }

    /** 从持久化快照完整还原聚合，不触发任何状态转换。 */
    public static ChargingTransaction restore(UUID id, UUID tenantId, UUID deviceId, String deviceCode,
                                              UUID connectorId, int connectorNo, DeviceProtocol protocol,
                                              UUID commandId, String businessRef, String protocolTransactionId,
                                              ChargingTransactionStatus status, Instant startedAt, Instant endedAt,
                                              BigDecimal startMeterKwh, BigDecimal endMeterKwh, BigDecimal energyKwh,
                                              Integer startSoc, Integer latestSoc, Integer endSoc,
                                              Duration chargingDuration, BigDecimal totalAmount,
                                              ChargingStopCategory stopCategory, Integer rawStopCode,
                                              String rawStopDetail, String vehicleVin,
                                              ChargingStartMethod startMethod, Instant lastProgressAt,
                                              Instant finalRecordReceivedAt, String rawFinalRecord,
                                              String additionalInfo, Instant createdAt,
                                              Instant updatedAt, long version) {
        return new ChargingTransaction(id, tenantId, deviceId, deviceCode, connectorId, connectorNo,
                protocol, commandId, businessRef, protocolTransactionId, status, startedAt, endedAt,
                startMeterKwh, endMeterKwh, energyKwh, startSoc, latestSoc, endSoc,
                chargingDuration, totalAmount, stopCategory, rawStopCode, rawStopDetail,
                vehicleVin, startMethod, lastProgressAt, finalRecordReceivedAt,
                rawFinalRecord, additionalInfo, createdAt, updatedAt, version);
    }

    public synchronized boolean bindProtocolTransactionId(String tradeNo, Instant now) {
        // 协议交易号可晚于平台交易创建，但一旦绑定便不可切换到另一设备交易。
        if (tradeNo == null || tradeNo.isBlank()) {
            return false;
        }
        if (protocolTransactionId != null && !protocolTransactionId.equals(tradeNo)) {
            throw new IllegalStateException("Transaction already bound to another protocol transaction id");
        }
        if (tradeNo.equals(protocolTransactionId)) {
            return false;
        }
        protocolTransactionId = tradeNo;
        updatedAt = now;
        return true;
    }

    /**
     * 应用设备侧开始交易的权威事实并进入 CHARGING。
     * OCPP StartTransaction 会走此路径；远程命令 ACK 本身不会调用它。
     */
    public synchronized void begin(String protocolId, Instant started, BigDecimal meterStart,
                                   ChargingStartMethod method, Instant now) {
        bindProtocolTransactionId(protocolId, now);
        if (startedAt == null) {
            startedAt = started == null ? now : started;
        }
        if (startMeterKwh == null) {
            startMeterKwh = meterStart;
        }
        if (commandId == null && method != null) {
            startMethod = method;
        }
        status = ChargingTransactionStatus.CHARGING;
        updatedAt = now;
    }

    /** 根据可信的连接器状态或充电进度，将启动中的交易推进为 CHARGING。 */
    public synchronized boolean markCharging(Instant started, Integer soc, Instant now) {
        if (status == ChargingTransactionStatus.COMPLETED || status == ChargingTransactionStatus.ABORTED
                || status == ChargingTransactionStatus.START_FAILED) {
            return false;
        }
        boolean changed = status != ChargingTransactionStatus.CHARGING;
        status = ChargingTransactionStatus.CHARGING;
        if (startedAt == null) {
            startedAt = started == null ? now : started;
            changed = true;
        }
        if (startSoc == null && soc != null) {
            startSoc = soc;
            changed = true;
        }
        if (soc != null) {
            latestSoc = soc;
        }
        updatedAt = now;
        return changed;
    }

    public synchronized boolean updateProgress(BigDecimal energy, Integer soc, Duration duration,
                                               BigDecimal amount, Instant now) {
        // 高频进度只更新测量快照，不改变已完成/已中止交易的终态。
        if (status == ChargingTransactionStatus.COMPLETED || status == ChargingTransactionStatus.ABORTED) {
            return false;
        }
        energyKwh = energy;
        latestSoc = soc;
        chargingDuration = duration;
        totalAmount = amount;
        lastProgressAt = now;
        updatedAt = now;
        return true;
    }

    /**
     * 记录真实充电过程已经结束并进入 ENDING，但仍等待最终交易记录。
     * 云快充 0x19 属于结束事实而非最终账单；0x3B 才提供权威最终记录。
     */
    public synchronized boolean markEnding(Instant ended, Integer soc, Duration duration,
                                           BigDecimal energy, String details, Instant now) {
        if (status == ChargingTransactionStatus.COMPLETED || status == ChargingTransactionStatus.ABORTED) {
            return false;
        }
        status = ChargingTransactionStatus.ENDING;
        endedAt = ended == null ? now : ended;
        endSoc = soc;
        latestSoc = soc == null ? latestSoc : soc;
        chargingDuration = duration == null ? chargingDuration : duration;
        energyKwh = energy == null ? energyKwh : energy;
        additionalInfo = details == null ? additionalInfo : details;
        updatedAt = now;
        return true;
    }

    public synchronized boolean markStartFailed(ChargingStopCategory category, Integer rawCode,
                                                String detail, Instant now) {
        // 只有尚未开始的 STARTING 交易可判定启动失败，已充电交易不能被拒绝结果回退。
        if (status != ChargingTransactionStatus.STARTING) {
            return false;
        }
        status = ChargingTransactionStatus.START_FAILED;
        stopCategory = category;
        rawStopCode = rawCode;
        rawStopDetail = detail;
        endedAt = now;
        updatedAt = now;
        return true;
    }

    /**
     * 应用最终权威交易记录，并区分首次处理、相同重传和关键字段冲突。
     * 冲突记录不会覆盖已完成交易的计量及金额数据。
     */
    public synchronized FinalizationResult finalizeRecord(Instant start, Instant end,
                                                          BigDecimal startMeter, BigDecimal endMeter,
                                                          BigDecimal totalEnergy, BigDecimal amount,
                                                          String vin, ChargingStartMethod method,
                                                          ChargingStopCategory category, int rawCode,
                                                          String rawDetail, Integer finalSoc,
                                                          Instant receivedAt, String rawRecord,
                                                          String details) {
        // 最终记录是计量和金额的权威来源；重复记录只比较关键字段，不覆盖首份已接受数据。
        if (status == ChargingTransactionStatus.COMPLETED) {
            boolean identical = sameIfProvided(startedAt, start)
                    && sameIfProvided(endedAt, end)
                    && equalIfProvided(startMeterKwh, startMeter)
                    && equalIfProvided(endMeterKwh, endMeter)
                    && equalIfProvided(energyKwh, totalEnergy)
                    && equalIfProvided(totalAmount, amount)
                    && Objects.equals(rawStopCode, rawCode);
            return identical ? FinalizationResult.DUPLICATE : FinalizationResult.CONFLICT;
        }
        startedAt = start == null ? startedAt : start;
        endedAt = end == null ? receivedAt : end;
        startMeterKwh = startMeter == null ? startMeterKwh : startMeter;
        endMeterKwh = endMeter == null ? endMeterKwh : endMeter;
        energyKwh = totalEnergy == null ? deriveEnergy(startMeterKwh, endMeterKwh) : totalEnergy;
        totalAmount = amount == null ? totalAmount : amount;
        vehicleVin = vin == null ? vehicleVin : vin;
        startMethod = method == null ? startMethod : method;
        stopCategory = category;
        rawStopCode = rawCode;
        rawStopDetail = rawDetail;
        endSoc = finalSoc == null ? endSoc : finalSoc;
        latestSoc = endSoc == null ? latestSoc : endSoc;
        finalRecordReceivedAt = receivedAt;
        rawFinalRecord = rawRecord;
        additionalInfo = details;
        status = ChargingTransactionStatus.COMPLETED;
        updatedAt = receivedAt;
        return FinalizationResult.APPLIED;
    }

    private boolean equalNumber(BigDecimal left, BigDecimal right) {
        return left == null ? right == null : right != null && left.compareTo(right) == 0;
    }

    private boolean equalIfProvided(BigDecimal current, BigDecimal provided) {
        return provided == null || equalNumber(current, provided);
    }

    private boolean sameIfProvided(Object current, Object provided) {
        return provided == null || Objects.equals(current, provided);
    }

    private BigDecimal deriveEnergy(BigDecimal startMeter, BigDecimal endMeter) {
        return startMeter == null || endMeter == null ? energyKwh : endMeter.subtract(startMeter);
    }

    public boolean active() {
        return status == ChargingTransactionStatus.STARTING
                || status == ChargingTransactionStatus.CHARGING
                || status == ChargingTransactionStatus.ENDING;
    }

    public enum FinalizationResult { APPLIED, DUPLICATE, CONFLICT }

    public UUID id() { return id; }
    public UUID tenantId() { return tenantId; }
    public UUID deviceId() { return deviceId; }
    public String deviceCode() { return deviceCode; }
    public UUID connectorId() { return connectorId; }
    public int connectorNo() { return connectorNo; }
    public DeviceProtocol protocol() { return protocol; }
    public UUID commandId() { return commandId; }
    public String businessRef() { return businessRef; }
    public String protocolTransactionId() { return protocolTransactionId; }
    public ChargingTransactionStatus status() { return status; }
    public Instant startedAt() { return startedAt; }
    public Instant endedAt() { return endedAt; }
    public BigDecimal startMeterKwh() { return startMeterKwh; }
    public BigDecimal endMeterKwh() { return endMeterKwh; }
    public BigDecimal energyKwh() { return energyKwh; }
    public Integer startSoc() { return startSoc; }
    public Integer latestSoc() { return latestSoc; }
    public Integer endSoc() { return endSoc; }
    public Duration chargingDuration() { return chargingDuration; }
    public BigDecimal totalAmount() { return totalAmount; }
    public ChargingStopCategory stopCategory() { return stopCategory; }
    public Integer rawStopCode() { return rawStopCode; }
    public String rawStopDetail() { return rawStopDetail; }
    public String vehicleVin() { return vehicleVin; }
    public ChargingStartMethod startMethod() { return startMethod; }
    public Instant lastProgressAt() { return lastProgressAt; }
    public Instant finalRecordReceivedAt() { return finalRecordReceivedAt; }
    public String rawFinalRecord() { return rawFinalRecord; }
    public String additionalInfo() { return additionalInfo; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }
    public long version() { return version; }
}
