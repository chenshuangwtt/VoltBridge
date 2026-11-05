package com.voltbridge.business.web.billing;

import com.voltbridge.business.application.billing.ActivateTariffPlanUseCase;
import com.voltbridge.business.application.billing.CreateFixedTariffPlanUseCase;
import com.voltbridge.business.application.billing.GetTariffPlanUseCase;
import com.voltbridge.business.application.billing.ListTariffPlansUseCase;
import com.voltbridge.business.domain.billing.TariffPlan;
import com.voltbridge.common.result.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 资费 MVP 的最小管理 API；改价请新建资费方案而非原地更新。 */
@RestController
@RequestMapping("/api/tariffs")
@Tag(name = "Tariffs", description = "Fixed-rate MVP tariff management")
@io.swagger.v3.oas.annotations.responses.ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Successful response", useReturnTypeSchema = true),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Invalid tariff request or business rule rejection; response uses the ApiResponse failure envelope",
                content = @io.swagger.v3.oas.annotations.media.Content(schema = @Schema(implementation = ApiResponse.class)))
})
public class TariffPlanController {
    private final CreateFixedTariffPlanUseCase create; private final ActivateTariffPlanUseCase activate;
    private final GetTariffPlanUseCase get; private final ListTariffPlansUseCase list;
    public TariffPlanController(CreateFixedTariffPlanUseCase create, ActivateTariffPlanUseCase activate, GetTariffPlanUseCase get, ListTariffPlansUseCase list) { this.create = create; this.activate = activate; this.get = get; this.list = list; }
    @PostMapping
    @Operation(summary = "Create a fixed-rate tariff", description = "Creates a tariff with separate energy and service fees. Tariff edits use a new plan; existing billing records retain their pricing snapshot.")
    public ApiResponse<TariffPlanView> create(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(description = "Fixed-rate tariff parameters", required = true,
                    content = @io.swagger.v3.oas.annotations.media.Content(schema = @Schema(implementation = CreateTariffRequest.class),
                            examples = @io.swagger.v3.oas.annotations.media.ExampleObject(value = "{\"tenantId\":\"11111111-1111-4111-8111-111111111111\",\"name\":\"Standard DC tariff\",\"currency\":\"CNY\",\"energyPricePerKwh\":1.00,\"serviceFeePerKwh\":0.20,\"effectiveFrom\":\"2026-11-05T00:00:00Z\",\"effectiveTo\":null}")))
            @Valid @RequestBody CreateTariffRequest request) {
        return ApiResponse.success(TariffPlanView.from(create.execute(request.tenantId(), request.name(), request.currency(), request.energyPricePerKwh(), request.serviceFeePerKwh(), request.effectiveFrom(), request.effectiveTo())));
    }

    @PostMapping("/{tariffPlanId}/activate")
    @Operation(summary = "Activate a tariff")
    public ApiResponse<TariffPlanView> activate(@PathVariable UUID tariffPlanId) { return ApiResponse.success(TariffPlanView.from(activate.execute(tariffPlanId))); }

    @GetMapping("/{tariffPlanId}")
    @Operation(summary = "Get a tariff")
    public ApiResponse<TariffPlanView> find(@PathVariable UUID tariffPlanId) { return ApiResponse.success(TariffPlanView.from(get.execute(tariffPlanId))); }

    @GetMapping
    @Operation(summary = "List tenant tariffs")
    public ApiResponse<List<TariffPlanView>> list(@Parameter(description = "Tenant UUID") @RequestParam UUID tenantId) { return ApiResponse.success(list.execute(tenantId).stream().map(TariffPlanView::from).toList()); }

    @Schema(description = "Fixed-rate tariff request")
    public record CreateTariffRequest(@NotNull UUID tenantId, @NotBlank String name, @NotBlank String currency, @NotNull @DecimalMin("0.00") BigDecimal energyPricePerKwh, @NotNull @DecimalMin("0.00") BigDecimal serviceFeePerKwh, @NotNull Instant effectiveFrom, Instant effectiveTo) { }
    @Schema(description = "Tariff plan with fixed energy and service fees")
    public record TariffPlanView(UUID id, UUID tenantId, String name, String currency, String pricingType, BigDecimal energyPricePerKwh, BigDecimal serviceFeePerKwh, Instant effectiveFrom, Instant effectiveTo, String status, long version) { static TariffPlanView from(TariffPlan value) { return new TariffPlanView(value.id(), value.tenantId(), value.name(), value.currency(), value.pricingType().name(), value.energyPricePerKwh(), value.serviceFeePerKwh(), value.effectiveFrom(), value.effectiveTo(), value.status().name(), value.version()); } }
}
