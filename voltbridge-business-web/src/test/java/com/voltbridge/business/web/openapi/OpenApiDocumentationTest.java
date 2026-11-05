package com.voltbridge.business.web.openapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltbridge.business.application.billing.ActivateTariffPlanUseCase;
import com.voltbridge.business.application.billing.BillCompletedTransactionUseCase;
import com.voltbridge.business.application.billing.CreateFixedTariffPlanUseCase;
import com.voltbridge.business.application.billing.GetBillingRecordUseCase;
import com.voltbridge.business.application.billing.GetTariffPlanUseCase;
import com.voltbridge.business.application.billing.ListTariffPlansUseCase;
import com.voltbridge.business.application.charging.StartChargingUseCase;
import com.voltbridge.business.application.charging.StopChargingUseCase;
import com.voltbridge.business.application.command.GetDeviceCommandUseCase;
import com.voltbridge.business.application.device.DeviceOperationCommandUseCase;
import com.voltbridge.business.application.device.FindChargingDeviceUseCase;
import com.voltbridge.business.application.transaction.GetChargingTransactionUseCase;
import com.voltbridge.business.web.billing.BillingRecordController;
import com.voltbridge.business.web.billing.TariffPlanController;
import com.voltbridge.business.web.command.DeviceCommandController;
import com.voltbridge.business.web.device.DeviceController;
import com.voltbridge.business.web.error.GlobalExceptionHandler;
import com.voltbridge.business.web.transaction.ChargingTransactionController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

@SpringBootTest(classes = OpenApiDocumentationTest.TestApplication.class)
@AutoConfigureMockMvc
class OpenApiDocumentationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean private FindChargingDeviceUseCase findChargingDevice;
    @MockitoBean private StartChargingUseCase startCharging;
    @MockitoBean private StopChargingUseCase stopCharging;
    @MockitoBean private DeviceOperationCommandUseCase deviceOperations;
    @MockitoBean private GetDeviceCommandUseCase getDeviceCommand;
    @MockitoBean private GetChargingTransactionUseCase getChargingTransaction;
    @MockitoBean private CreateFixedTariffPlanUseCase createTariffPlan;
    @MockitoBean private ActivateTariffPlanUseCase activateTariffPlan;
    @MockitoBean private GetTariffPlanUseCase getTariffPlan;
    @MockitoBean private ListTariffPlansUseCase listTariffPlans;
    @MockitoBean private GetBillingRecordUseCase getBillingRecord;
    @MockitoBean private BillCompletedTransactionUseCase billCompletedTransaction;

    @Test
    void publishesOpenApiPathsAndSwaggerUi() throws Exception {
        String document = mockMvc.perform(get("/v3/api-docs").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode paths = objectMapper.readTree(document).path("paths");

        assertThat(paths.has("/api/devices/{deviceCode}")).isTrue();
        assertThat(paths.has("/api/devices/{deviceCode}/commands/start-charging")).isTrue();
        assertThat(paths.has("/api/devices/{deviceCode}/commands/stop-charging")).isTrue();
        assertThat(paths.has("/api/devices/{deviceCode}/commands/time-sync")).isTrue();
        assertThat(paths.has("/api/devices/{deviceCode}/commands/reset")).isTrue();
        assertThat(paths.has("/api/commands/{commandId}")).isTrue();
        assertThat(paths.has("/api/transactions/{transactionId}")).isTrue();
        assertThat(paths.has("/api/transactions")).isTrue();
        assertThat(paths.has("/api/tariffs")).isTrue();
        assertThat(paths.has("/api/tariffs/{tariffPlanId}")).isTrue();
        assertThat(paths.has("/api/tariffs/{tariffPlanId}/activate")).isTrue();
        assertThat(paths.has("/api/billing-records/{billingRecordId}")).isTrue();
        assertThat(paths.has("/api/billing-records/by-transaction/{transactionId}")).isTrue();
        assertThat(paths.has("/api/billing-records/by-transaction/{transactionId}/retry")).isTrue();
        assertThat(objectMapper.readTree(document).path("info").path("title").asText()).isEqualTo("VoltBridge API");
        JsonNode start = paths.path("/api/devices/{deviceCode}/commands/start-charging").path("post");
        assertThat(start.path("description").asText()).contains("charging may not have started yet");
        assertThat(start.path("requestBody").path("content").path("application/json")
                .path("example").path("connectorNo").asText()).isEqualTo("01");
        assertThat(start.path("responses").has("200")).isTrue();
        assertThat(start.path("responses").has("400")).isTrue();

        String swaggerUi = mockMvc.perform(get("/swagger-ui/index.html"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(swaggerUi).contains("Swagger UI");
    }

    @Test
    void returnsStandardErrorEnvelopeForMissingRequiredQueryParameter() throws Exception {
        mockMvc.perform(get("/api/tariffs"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude = {
            DataSourceAutoConfiguration.class,
            FlywayAutoConfiguration.class,
            RedisAutoConfiguration.class
    })
    @Import({
            OpenApiConfiguration.class,
            DeviceController.class,
            DeviceCommandController.class,
            ChargingTransactionController.class,
            TariffPlanController.class,
            BillingRecordController.class,
            GlobalExceptionHandler.class
    })
    static class TestApplication {
    }
}
