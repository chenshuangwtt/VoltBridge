package com.voltbridge.business.web.billing;

import com.voltbridge.business.application.billing.CreateFallbackTariffRevisionUseCase;
import com.voltbridge.business.application.billing.GetFallbackTariffUseCase;
import com.voltbridge.business.domain.billing.TariffPlan;
import com.voltbridge.common.result.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** System-managed fallback tariff inspection and fixed-price revision API. */
@RestController
@RequestMapping("/api/tenants/{tenantId}/fallback-tariff")
@Tag(name = "Fallback tariff", description = "System-managed fixed tariff used when no scheduled tariff matches.")
public class FallbackTariffController {
    private final GetFallbackTariffUseCase getFallbackTariff;
    private final CreateFallbackTariffRevisionUseCase createRevision;

    public FallbackTariffController(GetFallbackTariffUseCase getFallbackTariff,
                                    CreateFallbackTariffRevisionUseCase createRevision) {
        this.getFallbackTariff = getFallbackTariff;
        this.createRevision = createRevision;
    }

    @GetMapping
    @Operation(summary = "Get the fallback tariff", description = "Returns the FALLBACK revision covering effectiveAt, or platform current time when effectiveAt is omitted.")
    public ApiResponse<TariffPlanController.TariffPlanView> get(
            @PathVariable UUID tenantId, @RequestParam(required = false) Instant effectiveAt) {
        TariffPlan plan = getFallbackTariff.execute(tenantId, effectiveAt);
        return ApiResponse.success(TariffPlanController.TariffPlanView.from(plan));
    }

    @PostMapping("/revisions")
    @Operation(summary = "Schedule a fallback price revision", description = "Atomically closes the current FALLBACK revision at switchAt and creates the next all-day FIXED revision. The role, currency, timezone, and full-day period are system controlled.")
    public ApiResponse<TariffPlanController.TariffPlanView> createRevision(
            @PathVariable UUID tenantId, @Valid @RequestBody CreateFallbackTariffRevisionRequest request) {
        TariffPlan plan = createRevision.execute(tenantId, request.switchAt(),
                request.energyPricePerKwh(), request.serviceFeePerKwh());
        return ApiResponse.success(TariffPlanController.TariffPlanView.from(plan));
    }

    @Schema(description = "Price and effective switch instant for a new fallback revision")
    public record CreateFallbackTariffRevisionRequest(@NotNull Instant switchAt,
                                                       @NotNull @DecimalMin("0.00") BigDecimal energyPricePerKwh,
                                                       @NotNull @DecimalMin("0.00") BigDecimal serviceFeePerKwh) { }
}
