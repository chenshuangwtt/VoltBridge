package com.voltbridge.business.web.transaction;

import com.voltbridge.business.application.transaction.GetChargingTransactionUseCase;
import com.voltbridge.business.domain.transaction.ChargingTransaction;
import com.voltbridge.business.domain.transaction.ChargingTransactionStatus;
import com.voltbridge.common.result.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 充电交易只读查询接口。
 *
 * <p>响应使用平台标准交易视图，不向调用方暴露云快充报文 DTO 或二进制字段。</p>
 */
@RestController
@RequestMapping("/api/transactions")
public class ChargingTransactionController {
    private final GetChargingTransactionUseCase useCase;

    public ChargingTransactionController(GetChargingTransactionUseCase useCase) {
        this.useCase = useCase;
    }

    @GetMapping("/{transactionId}")
    public ApiResponse<TransactionView> find(@PathVariable UUID transactionId) {
        return ApiResponse.success(TransactionView.from(useCase.find(transactionId)));
    }

    @GetMapping
    public ApiResponse<List<TransactionView>> search(
            @RequestParam(required = false) String deviceCode,
            @RequestParam(required = false) Integer connectorNo,
            @RequestParam(required = false) ChargingTransactionStatus status) {
        return ApiResponse.success(useCase.search(deviceCode, connectorNo, status).stream()
                .map(TransactionView::from).toList());
    }

    /** 面向 API 的稳定交易快照，协议诊断扩展统一收敛在 additionalInfo。 */
    public record TransactionView(UUID id, String deviceCode, int connectorNo, String protocol,
                                  String protocolTransactionId, String businessRef, String status,
                                  Instant startedAt, Instant endedAt, BigDecimal startMeterKwh,
                                  BigDecimal endMeterKwh, BigDecimal energyKwh, Integer startSoc,
                                  Integer latestSoc, Integer endSoc, Long chargingDurationSeconds,
                                  BigDecimal totalAmount, String stopCategory, Integer rawStopCode,
                                  String vehicleVin, String startMethod, Instant finalRecordReceivedAt,
                                  String additionalInfo) {
        static TransactionView from(ChargingTransaction transaction) {
            return new TransactionView(transaction.id(), transaction.deviceCode(), transaction.connectorNo(),
                    transaction.protocol().name(), transaction.protocolTransactionId(), transaction.businessRef(),
                    transaction.status().name(), transaction.startedAt(), transaction.endedAt(),
                    transaction.startMeterKwh(), transaction.endMeterKwh(), transaction.energyKwh(),
                    transaction.startSoc(), transaction.latestSoc(), transaction.endSoc(),
                    transaction.chargingDuration() == null ? null : transaction.chargingDuration().toSeconds(),
                    transaction.totalAmount(), transaction.stopCategory() == null ? null : transaction.stopCategory().name(),
                    transaction.rawStopCode(), transaction.vehicleVin(),
                    transaction.startMethod() == null ? null : transaction.startMethod().name(),
                    transaction.finalRecordReceivedAt(), transaction.additionalInfo());
        }
    }
}
