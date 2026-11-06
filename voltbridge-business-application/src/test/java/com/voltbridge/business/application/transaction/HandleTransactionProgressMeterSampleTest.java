package com.voltbridge.business.application.transaction;

import com.voltbridge.business.domain.billing.ChargingMeterSample;
import com.voltbridge.business.domain.billing.ChargingMeterSampleRepository;
import com.voltbridge.business.domain.transaction.ChargingTransaction;
import com.voltbridge.device.event.ChargingProgressEvent;
import com.voltbridge.device.model.DeviceId;
import com.voltbridge.device.model.DeviceProtocol;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HandleTransactionProgressMeterSampleTest {
    @Test
    void onlyPersistsEnergySamplesAfterTransactionResolution() {
        ChargingTransactionResolver resolver = mock(ChargingTransactionResolver.class);
        ChargingStartedService started = mock(ChargingStartedService.class);
        ChargingMeterSampleRepository samples = mock(ChargingMeterSampleRepository.class);
        ChargingTransaction transaction = mock(ChargingTransaction.class);
        UUID transactionId = UUID.randomUUID();
        when(transaction.id()).thenReturn(transactionId);
        DeviceId device = new DeviceId("VB-METER-001");
        Instant at = Instant.parse("2026-11-01T06:50:00Z");
        HandleTransactionProgressUseCase handler = new HandleTransactionProgressUseCase(resolver, started, samples);

        when(resolver.resolve(eq(device), eq(DeviceProtocol.OCPP_16), eq(1), eq("P-1"), eq(at)))
                .thenReturn(Optional.of(transaction));
        handler.handle(event(UUID.randomUUID(), device, at, null));
        verify(started).confirm(transaction, 42, at);
        verify(samples, never()).saveIfAbsent(any());

        UUID eventId = UUID.randomUUID();
        handler.handle(event(eventId, device, at, new BigDecimal("12.345678")));
        ArgumentCaptor<ChargingMeterSample> captor = ArgumentCaptor.forClass(ChargingMeterSample.class);
        verify(samples).saveIfAbsent(captor.capture());
        assertThat(captor.getValue().eventId()).isEqualTo(eventId);
        assertThat(captor.getValue().transactionId()).isEqualTo(transactionId);
        assertThat(captor.getValue().energyKwh()).isEqualByComparingTo("12.345678");
        assertThat(captor.getValue().protocol()).isEqualTo(DeviceProtocol.OCPP_16);

        when(resolver.resolve(eq(device), eq(DeviceProtocol.OCPP_16), eq(1), eq("P-2"), eq(at)))
                .thenReturn(Optional.empty());
        handler.handle(event(UUID.randomUUID(), device, at, new BigDecimal("13.000000"), "P-2"));
        verify(samples).saveIfAbsent(any(ChargingMeterSample.class));
    }

    private ChargingProgressEvent event(UUID eventId, DeviceId device, Instant at, BigDecimal energy) {
        return event(eventId, device, at, energy, "P-1");
    }

    private ChargingProgressEvent event(UUID eventId, DeviceId device, Instant at, BigDecimal energy, String tradeNo) {
        return new ChargingProgressEvent(eventId, device, DeviceProtocol.OCPP_16, 1, tradeNo,
                null, null, new BigDecimal("7.2"), 42, energy, null, null, "{}", at);
    }
}
