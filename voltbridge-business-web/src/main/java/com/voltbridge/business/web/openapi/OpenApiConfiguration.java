package com.voltbridge.business.web.openapi;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.info.License;
import org.springframework.context.annotation.Configuration;

@Configuration
@OpenAPIDefinition(info = @Info(
        title = "VoltBridge API",
        description = "REST API for device operations, charging transactions, tariffs and billing.",
        version = "0.1.0",
        license = @License(name = "Apache License 2.0", url = "https://www.apache.org/licenses/LICENSE-2.0")))
public class OpenApiConfiguration {
}
