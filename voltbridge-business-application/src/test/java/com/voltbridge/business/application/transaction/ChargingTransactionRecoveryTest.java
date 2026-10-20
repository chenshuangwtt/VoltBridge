package com.voltbridge.business.application.transaction;

import com.voltbridge.business.domain.connector.Connector;
import com.voltbridge.business.domain.connector.ConnectorRepository;
import com.voltbridge.business.domain.device.ChargingDevice;
import com.voltbridge.business.domain.device.ChargingDeviceRepository;
import com.voltbridge.business.domain.transaction.ChargingTransaction;
import com.voltbridge.business.domain.transaction.ChargingTransactionRepository;
import com.voltbridge.business.domain.transaction.ChargingTransactionStatus;
import com.voltbridge.device.event.EnergyBreakdownItem;
import com.voltbridge.device.event.TransactionRecordEvent;
import com.voltbridge.device.model.ChargingStartMethod;
import com.voltbridge.device.model.ChargingStopCategory;
import com.voltbridge.device.model.DeviceId;
import com.voltbridge.device.model.DeviceProtocol;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ChargingTransactionRecoveryTest {

    private static final String DEVICE_CODE = "20231212000010";
    private static final String TRADE_NO = "12345678901234567890123456789012";

    @Test
    void finalizesPersistedTransactionWithoutRuntimeStateAndKeepsDuplicateIdempotent() {
        Instant now = Instant.parse("2026-10-18T08:00:00Z");
        ChargingDevice device = new ChargingDevice(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                DEVICE_CODE, "Recovery device", DeviceProtocol.YUN_KUAI_CHONG_150,
                "1.5", "ONLINE", now, now, 0);
        Connector connector = new Connector(UUID.randomUUID(), device.tenantId(), device.stationId(),
                device.id(), 1, "CONNECTOR-01", "DC", new BigDecimal("120"),
                "CHARGING", now, now);
        InMemoryTransactionRepository transactions = new InMemoryTransactionRepository();
        ChargingTransaction persisted = ChargingTransaction.start(device.tenantId(), device.id(),
                device.deviceCode(), connector.id(), connector.connectorNo(), device.protocol(),
                UUID.randomUUID(), "ORDER-RECOVERY", now.minusSeconds(3600));
        persisted.bindProtocolTransactionId(TRADE_NO, now.minusSeconds(3590));
        persisted.markCharging(now.minusSeconds(3500), 20, now.minusSeconds(3500));
        persisted.markEnding(now.minusSeconds(10), 80, null,
                new BigDecimal("10.0000"), "{}", now.minusSeconds(10));
        transactions.save(persisted);

        ChargingTransactionResolver resolver = new ChargingTransactionResolver(
                new FixedDeviceRepository(device), new FixedConnectorRepository(connector), transactions);
        HandleTransactionRecordUseCase handler = new HandleTransactionRecordUseCase(
                resolver, transactions, mock(com.voltbridge.business.domain.command.DeviceCommandRepository.class));
        TransactionRecordEvent finalRecord = record(now, new BigDecimal("23.4567"));

        handler.handle(finalRecord);
        handler.handle(finalRecord);
        handler.handle(record(now.plusSeconds(1), new BigDecimal("99.9999")));

        assertThat(transactions.values()).hasSize(1);
        ChargingTransaction completed = transactions.values().getFirst();
        assertThat(completed.id()).isEqualTo(persisted.id());
        assertThat(completed.status()).isEqualTo(ChargingTransactionStatus.COMPLETED);
        assertThat(completed.stopCategory()).isEqualTo(ChargingStopCategory.EMERGENCY_STOP);
        assertThat(completed.rawStopCode()).isEqualTo(0x72);
        assertThat(completed.totalAmount()).isEqualByComparingTo("23.4567");
        assertThat(completed.additionalInfo()).contains("energyBreakdown", "protocolDetail");
        assertThat(completed.rawFinalRecord()).contains("A1B2C3");
    }

    private TransactionRecordEvent record(Instant occurredAt, BigDecimal amount) {
        return new TransactionRecordEvent(UUID.randomUUID(), new DeviceId(DEVICE_CODE),
                DeviceProtocol.YUN_KUAI_CHONG_150, 1, TRADE_NO,
                Instant.parse("2026-10-18T02:15:30Z"), Instant.parse("2026-10-18T03:45:30Z"),
                new BigDecimal("12345.6789"), new BigDecimal("12355.6789"),
                new BigDecimal("10.0000"), amount, "LTESTVIN123456789",
                ChargingStartMethod.REMOTE, ChargingStopCategory.EMERGENCY_STOP, 0x72,
                "emergency stop", Instant.parse("2026-10-18T03:45:31Z"),
                List.of(new EnergyBreakdownItem("TOP", new BigDecimal("2.5000"),
                        new BigDecimal("3.0864"), new BigDecimal("1.23456"))),
                "A1B2C3", "{\"totalLossEnergyKwh\":0.0460}", occurredAt);
    }

    private static final class InMemoryTransactionRepository implements ChargingTransactionRepository {
        private final List<ChargingTransaction> values = new ArrayList<>();

        @Override public void save(ChargingTransaction transaction) { values.removeIf(item -> item.id().equals(transaction.id())); values.add(transaction); }
        @Override public Optional<ChargingTransaction> findById(UUID id) { return values.stream().filter(item -> item.id().equals(id)).findFirst(); }
        @Override public Optional<ChargingTransaction> findByCommandId(UUID id) { return values.stream().filter(item -> id.equals(item.commandId())).findFirst(); }
        @Override public Optional<ChargingTransaction> findByProtocolTransactionId(DeviceProtocol protocol, UUID deviceId, String tradeNo) { return values.stream().filter(item -> item.protocol() == protocol && item.deviceId().equals(deviceId) && tradeNo.equals(item.protocolTransactionId())).findFirst(); }
        @Override public Optional<ChargingTransaction> findActiveByDeviceAndConnector(UUID deviceId, UUID connectorId) { return values.stream().filter(ChargingTransaction::active).filter(item -> item.deviceId().equals(deviceId) && item.connectorId().equals(connectorId)).findFirst(); }
        @Override public List<ChargingTransaction> find(String deviceCode, Integer connectorNo, ChargingTransactionStatus status) { return values.stream().filter(item -> deviceCode == null || deviceCode.equals(item.deviceCode())).filter(item -> connectorNo == null || connectorNo == item.connectorNo()).filter(item -> status == null || status == item.status()).toList(); }
        List<ChargingTransaction> values() { return List.copyOf(values); }
    }

    private record FixedDeviceRepository(ChargingDevice device) implements ChargingDeviceRepository {
        @Override public Optional<ChargingDevice> findById(UUID id) { return device.id().equals(id) ? Optional.of(device) : Optional.empty(); }
        @Override public Optional<ChargingDevice> findByDeviceCode(String deviceCode) { return device.deviceCode().equals(deviceCode) ? Optional.of(device) : Optional.empty(); }
        @Override public boolean updateStatus(String deviceCode, String status, Instant updatedAt) { return false; }
    }

    private record FixedConnectorRepository(Connector connector) implements ConnectorRepository {
        @Override public Optional<Connector> findByDeviceIdAndConnectorNo(UUID deviceId, int connectorNo) {
            return connector.deviceId().equals(deviceId) && connector.connectorNo() == connectorNo
                    ? Optional.of(connector) : Optional.empty();
        }
    }
}
