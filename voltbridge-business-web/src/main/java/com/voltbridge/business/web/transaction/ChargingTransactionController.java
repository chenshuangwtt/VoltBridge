package com.voltbridge.business.web.transaction;

import com.voltbridge.business.application.transaction.GetChargingTransactionUseCase;
import com.voltbridge.business.domain.transaction.ChargingTransaction;
import com.voltbridge.business.domain.transaction.ChargingTransactionStatus;
import com.voltbridge.common.result.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "Charging transactions", description = "Platform-level charging transaction lifecycle and queries")
@io.swagger.v3.oas.annotations.responses.ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Successful response", useReturnTypeSchema = true),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Invalid filter or business rejection; response uses the ApiResponse failure envelope",
                content = @io.swagger.v3.oas.annotations.media.Content(schema = @Schema(implementation = ApiResponse.class)))
})
public class ChargingTransactionController {
    private final GetChargingTransactionUseCase useCase;

    public ChargingTransactionController(GetChargingTransactionUseCase useCase) {
        this.useCase = useCase;
    }

    @GetMapping("/{transactionId}")
    @Operation(summary = "Get a charging transaction", description = "Returns the generic platform transaction lifecycle; protocol-specific details remain in additionalInfo.")
    public ApiResponse<TransactionView> find(@PathVariable UUID transactionId) {
        return ApiResponse.success(TransactionView.from(useCase.find(transactionId)));
    }

    @GetMapping
    @Operation(summary = "Search charging transactions", description = "Filters are optional and combined with AND.")
    public ApiResponse<List<TransactionView>> search(
            @Parameter(description = "Platform device code")
            @RequestParam(required = false) String deviceCode,
            @Parameter(description = "Connector number")
            @RequestParam(required = false) Integer connectorNo,
            @Parameter(description = "Platform lifecycle state")
            @RequestParam(required = false) ChargingTransactionStatus status) {
        return ApiResponse.success(useCase.search(deviceCode, connectorNo, status).stream()
                .map(TransactionView::from).toList());
    }

    /** 面向 API 的稳定交易快照，协议诊断扩展统一收敛在 additionalInfo。 */
    @Schema(description = "ChargingTransaction lifecycle state: STARTING, CHARGING, ENDING, COMPLETED, START_FAILED, or ABORTED")
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
