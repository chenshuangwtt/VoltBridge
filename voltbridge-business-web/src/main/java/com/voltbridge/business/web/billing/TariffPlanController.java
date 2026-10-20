package com.voltbridge.business.web.billing;

import com.voltbridge.business.application.billing.ActivateTariffPlanUseCase;
import com.voltbridge.business.application.billing.CreateFixedTariffPlanUseCase;
import com.voltbridge.business.application.billing.GetTariffPlanUseCase;
import com.voltbridge.business.application.billing.ListTariffPlansUseCase;
import com.voltbridge.business.domain.billing.TariffPlan;
import com.voltbridge.common.result.ApiResponse;
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
public class TariffPlanController {
    private final CreateFixedTariffPlanUseCase create; private final ActivateTariffPlanUseCase activate;
    private final GetTariffPlanUseCase get; private final ListTariffPlansUseCase list;
    public TariffPlanController(CreateFixedTariffPlanUseCase create, ActivateTariffPlanUseCase activate, GetTariffPlanUseCase get, ListTariffPlansUseCase list) { this.create = create; this.activate = activate; this.get = get; this.list = list; }
    @PostMapping public ApiResponse<TariffPlanView> create(@Valid @RequestBody CreateTariffRequest request) { return ApiResponse.success(TariffPlanView.from(create.execute(request.tenantId(), request.name(), request.currency(), request.energyPricePerKwh(), request.serviceFeePerKwh(), request.effectiveFrom(), request.effectiveTo()))); }
    @PostMapping("/{tariffPlanId}/activate") public ApiResponse<TariffPlanView> activate(@PathVariable UUID tariffPlanId) { return ApiResponse.success(TariffPlanView.from(activate.execute(tariffPlanId))); }
    @GetMapping("/{tariffPlanId}") public ApiResponse<TariffPlanView> find(@PathVariable UUID tariffPlanId) { return ApiResponse.success(TariffPlanView.from(get.execute(tariffPlanId))); }
    @GetMapping public ApiResponse<List<TariffPlanView>> list(@RequestParam UUID tenantId) { return ApiResponse.success(list.execute(tenantId).stream().map(TariffPlanView::from).toList()); }
    public record CreateTariffRequest(@NotNull UUID tenantId, @NotBlank String name, @NotBlank String currency, @NotNull @DecimalMin("0.00") BigDecimal energyPricePerKwh, @NotNull @DecimalMin("0.00") BigDecimal serviceFeePerKwh, @NotNull Instant effectiveFrom, Instant effectiveTo) { }
    public record TariffPlanView(UUID id, UUID tenantId, String name, String currency, String pricingType, BigDecimal energyPricePerKwh, BigDecimal serviceFeePerKwh, Instant effectiveFrom, Instant effectiveTo, String status, long version) { static TariffPlanView from(TariffPlan value) { return new TariffPlanView(value.id(), value.tenantId(), value.name(), value.currency(), value.pricingType().name(), value.energyPricePerKwh(), value.serviceFeePerKwh(), value.effectiveFrom(), value.effectiveTo(), value.status().name(), value.version()); } }
}
