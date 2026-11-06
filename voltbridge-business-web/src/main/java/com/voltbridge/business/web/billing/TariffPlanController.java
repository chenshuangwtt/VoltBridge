package com.voltbridge.business.web.billing;

import com.voltbridge.business.application.billing.ActivateTariffPlanUseCase;
import com.voltbridge.business.application.billing.CreateTariffPlanUseCase;
import com.voltbridge.business.application.billing.CreateTariffRevisionUseCase;
import com.voltbridge.business.application.billing.DeleteTariffPlanUseCase;
import com.voltbridge.business.application.billing.GetTariffPlanUseCase;
import com.voltbridge.business.application.billing.ListTariffPlansUseCase;
import com.voltbridge.business.application.billing.RetireTariffPlanUseCase;
import com.voltbridge.business.domain.billing.PricingType;
import com.voltbridge.business.domain.billing.TariffPeriod;
import com.voltbridge.business.domain.billing.TariffPeriodType;
import com.voltbridge.business.domain.billing.TariffPlan;
import com.voltbridge.business.domain.billing.TariffRole;
import com.voltbridge.common.result.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 版本化固定与每日分时资费管理 API。 */
@RestController
@RequestMapping("/api/tariffs")
@Tag(name = "Tariffs", description = "Versioned fixed-rate and time-of-use tariff management")
@io.swagger.v3.oas.annotations.responses.ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Successful response", useReturnTypeSchema = true),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Invalid tariff or business rule rejection; response uses the ApiResponse failure envelope",
                content = @io.swagger.v3.oas.annotations.media.Content(schema = @Schema(implementation = ApiResponse.class)))
})
public class TariffPlanController {
    private final CreateTariffPlanUseCase create;
    private final CreateTariffRevisionUseCase createRevision;
    private final ActivateTariffPlanUseCase activate;
    private final RetireTariffPlanUseCase retire;
    private final DeleteTariffPlanUseCase delete;
    private final GetTariffPlanUseCase get;
    private final ListTariffPlansUseCase list;

    public TariffPlanController(CreateTariffPlanUseCase create,
                                CreateTariffRevisionUseCase createRevision,
                                ActivateTariffPlanUseCase activate,
                                RetireTariffPlanUseCase retire,
                                DeleteTariffPlanUseCase delete,
                                GetTariffPlanUseCase get,
                                ListTariffPlansUseCase list) {
        this.create = create;
        this.createRevision = createRevision;
        this.activate = activate;
        this.retire = retire;
        this.delete = delete;
        this.get = get;
        this.list = list;
    }

    @PostMapping
    @Operation(summary = "Create a scheduled tariff plan", description = "Creates a SCHEDULED draft tariff whose periods cover the complete local day. FALLBACK tariffs are system initialized and cannot be created through this endpoint.")
    public ApiResponse<TariffPlanView> create(@Valid @RequestBody CreateTariffPlanRequest request) {
        TariffPlan plan = create.execute(request.tenantId(), request.name(), request.currency(),
                request.pricingType(), request.timezone(), periods(request.periods()),
                request.effectiveFrom(), request.effectiveTo());
        return ApiResponse.success(TariffPlanView.from(plan));
    }

    @PostMapping("/{tariffPlanId}/revisions")
    @Operation(summary = "Create the next scheduled tariff revision", description = "Creates an immutable SCHEDULED draft revision in the source family. FALLBACK revisions must use the tenant fallback revision endpoint.")
    public ApiResponse<TariffPlanView> createRevision(@PathVariable UUID tariffPlanId,
                                                       @Valid @RequestBody CreateTariffRevisionRequest request) {
        TariffPlan plan = createRevision.execute(tariffPlanId, request.name(), request.currency(),
                request.pricingType(), request.timezone(), periods(request.periods()),
                request.effectiveFrom(), request.effectiveTo());
        return ApiResponse.success(TariffPlanView.from(plan));
    }

    @PostMapping("/{tariffPlanId}/activate")
    @Operation(summary = "Activate a tariff revision", description = "Activates a SCHEDULED draft and fails if its effective range overlaps another ACTIVE SCHEDULED tariff for the same tenant. Effective ranges are left-closed and right-open.")
    public ApiResponse<TariffPlanView> activate(@PathVariable UUID tariffPlanId) {
        return ApiResponse.success(TariffPlanView.from(activate.execute(tariffPlanId)));
    }

    @PostMapping("/{tariffPlanId}/retire")
    @Operation(summary = "Retire a scheduled tariff revision", description = "Retires an ACTIVE SCHEDULED tariff. When no SCHEDULED tariff matches a subsequent transaction, the tenant FALLBACK tariff is resolved automatically. Historical transactions still resolve the revision that covered their start time. FALLBACK tariffs cannot be retired through this endpoint.")
    public ApiResponse<TariffPlanView> retire(@PathVariable UUID tariffPlanId) {
        return ApiResponse.success(TariffPlanView.from(retire.execute(tariffPlanId)));
    }

    @DeleteMapping("/{tariffPlanId}")
    @Operation(summary = "Delete a draft tariff revision", description = "Only DRAFT tariffs can be deleted. ACTIVE and RETIRED tariff plans are retained for lifecycle and historical billing integrity.")
    public ApiResponse<Void> delete(@PathVariable UUID tariffPlanId) {
        delete.execute(tariffPlanId);
        return ApiResponse.success(null);
    }

    @GetMapping("/{tariffPlanId}")
    @Operation(summary = "Get a tariff revision")
    public ApiResponse<TariffPlanView> find(@PathVariable UUID tariffPlanId) {
        return ApiResponse.success(TariffPlanView.from(get.execute(tariffPlanId)));
    }

    @GetMapping
    @Operation(summary = "List tenant tariff revisions")
    public ApiResponse<List<TariffPlanView>> list(@Parameter(description = "Tenant UUID") @RequestParam UUID tenantId) {
        return ApiResponse.success(list.execute(tenantId).stream().map(TariffPlanView::from).toList());
    }

    private List<TariffPeriod> periods(List<TariffPeriodRequest> requests) {
        return java.util.stream.IntStream.range(0, requests.size())
                .mapToObj(index -> requests.get(index).toDomain(index + 1))
                .toList();
    }

    @Schema(description = "Create a fixed or time-of-use tariff plan")
    public record CreateTariffPlanRequest(@NotNull UUID tenantId, @NotBlank String name,
                                          @NotBlank String currency, @NotNull PricingType pricingType,
                                          @NotBlank String timezone, @NotNull Instant effectiveFrom,
                                          Instant effectiveTo,
                                          @NotEmpty List<@Valid TariffPeriodRequest> periods) { }

    @Schema(description = "Create a revision in the source tariff family")
    public record CreateTariffRevisionRequest(@NotBlank String name, @NotBlank String currency,
                                              @NotNull PricingType pricingType, @NotBlank String timezone,
                                              @NotNull Instant effectiveFrom, Instant effectiveTo,
                                              @NotEmpty List<@Valid TariffPeriodRequest> periods) { }

    @Schema(description = "A price period in local-day minutes")
    public record TariffPeriodRequest(@NotNull TariffPeriodType periodType,
                                      @NotNull @Min(0) Integer startMinute,
                                      @NotNull @Min(1) Integer endMinute,
                                      @NotNull @DecimalMin("0.00") BigDecimal energyPricePerKwh,
                                      @NotNull @DecimalMin("0.00") BigDecimal serviceFeePerKwh) {
        private TariffPeriod toDomain(int sequenceNo) {
            return new TariffPeriod(sequenceNo, periodType, startMinute, endMinute,
                    energyPricePerKwh, serviceFeePerKwh);
        }
    }

    @Schema(description = "Versioned tariff plan and its complete daily periods")
    public record TariffPlanView(UUID id, UUID familyId, int revision, UUID tenantId,
                                 @Schema(description = "FALLBACK is the system-managed fixed tariff used when no scheduled tariff matches. SCHEDULED is an operator-managed fixed or time-of-use tariff with effective-date scheduling.") TariffRole role,
                                 String name,
                                 String currency, String pricingType, String timezone,
                                 Instant effectiveFrom, Instant effectiveTo, String status,
                                 List<TariffPeriodView> periods, long version) {
        public static TariffPlanView from(TariffPlan value) {
            return new TariffPlanView(value.id(), value.familyId(), value.revision(), value.tenantId(),
                    value.role(), value.name(), value.currency(), value.pricingType().name(), value.timezone(),
                    value.effectiveFrom(), value.effectiveTo(), value.status().name(),
                    value.periods().stream().map(TariffPeriodView::from).toList(), value.version());
        }
    }

    public record TariffPeriodView(int sequenceNo, String periodType, int startMinute, int endMinute,
                                   BigDecimal energyPricePerKwh, BigDecimal serviceFeePerKwh) {
        private static TariffPeriodView from(TariffPeriod period) {
            return new TariffPeriodView(period.sequenceNo(), period.periodType().name(), period.startMinute(),
                    period.endMinute(), period.energyPricePerKwh(), period.serviceFeePerKwh());
        }
    }
}
