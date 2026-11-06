package com.voltbridge.business.application.transaction;

import com.voltbridge.business.domain.billing.ChargingMeterSample;
import com.voltbridge.business.domain.billing.ChargingMeterSampleRepository;
import com.voltbridge.device.event.ChargingProgressEvent;
import com.voltbridge.device.port.DeviceEventHandler;
import org.springframework.stereotype.Service;

/**
 * 使用带 tradeNo 的实时充电进度确认交易已经开始。
 *
 * <p>高频进度明细仍由运行态存储维护，本处理器只推进关键状态，避免每帧写主交易表。</p>
 */
@Service
public class HandleTransactionProgressUseCase implements DeviceEventHandler<ChargingProgressEvent> {
    private final ChargingTransactionResolver resolver;
    private final ChargingStartedService chargingStartedService;
    private final ChargingMeterSampleRepository meterSamples;

    public HandleTransactionProgressUseCase(ChargingTransactionResolver resolver,
                                            ChargingStartedService chargingStartedService,
                                            ChargingMeterSampleRepository meterSamples) {
        this.resolver = resolver;
        this.chargingStartedService = chargingStartedService;
        this.meterSamples = meterSamples;
    }

    @Override public Class<ChargingProgressEvent> eventType() { return ChargingProgressEvent.class; }

    @Override
    public void handle(ChargingProgressEvent event) {
        resolver.resolve(event.deviceId(), event.protocol(),
                        event.connectorNo(), event.transactionId(), event.occurredAt())
                .ifPresent(transaction -> {
                    chargingStartedService.confirm(transaction, event.stateOfCharge(), event.occurredAt());
                    if (event.energyKwh() != null) {
                        meterSamples.saveIfAbsent(new ChargingMeterSample(event.eventId(), transaction.id(),
                                event.occurredAt(), event.energyKwh(), event.outputPowerKw(),
                                event.stateOfCharge(), event.protocol()));
                    }
                });
    }
}
