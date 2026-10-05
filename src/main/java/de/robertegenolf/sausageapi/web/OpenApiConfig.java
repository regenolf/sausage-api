package de.robertegenolf.sausageapi.web;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * API-Dokumentation unter /swagger-ui.html (JSON unter /v3/api-docs).
 */
@Configuration
class OpenApiConfig {

	@Bean
	OpenAPI sausageOpenApi() {
		return new OpenAPI()
				.info(new Info()
						.title("Sausage API")
						.description("Bratwurstbuden und andere Spots finden, bewerten und kommentieren")
						.version("v1"))
				.components(new Components().addSecuritySchemes("basicAuth",
						new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("basic")))
				.addSecurityItem(new SecurityRequirement().addList("basicAuth"));
	}
}
