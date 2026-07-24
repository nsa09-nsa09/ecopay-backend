package kz.hrms.splitupauth.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.servers.Server;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

  @Bean
  public OpenAPI splitUpOpenAPI(
      @Value("${app.brand.name:EcoPay}") String brandName,
      @Value("${app.brand.support-email:}") String supportEmail,
      @Value("${app.brand.public-url:}") String publicUrl) {
    final String bearerSchemeName = "bearerAuth";
    Contact contact = new Contact().name(brandName + " Backend");
    if (supportEmail != null && !supportEmail.isBlank()) {
      contact.email(supportEmail.trim());
    }

    OpenAPI api =
        new OpenAPI()
        .info(
            new Info()
                .title(brandName + " API")
                .version("v1")
                .description("REST API for " + brandName + " subscription sharing platform")
                .contact(contact)
                .license(new License().name("Proprietary")))
        .addSecurityItem(new SecurityRequirement().addList(bearerSchemeName))
        .components(
            new Components()
                .addSecuritySchemes(
                    bearerSchemeName,
                    new SecurityScheme()
                        .name(bearerSchemeName)
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")));
    if (publicUrl != null && !publicUrl.isBlank()) {
      api.addServersItem(new Server().url(publicUrl.trim()));
    }
    return api;
  }
}
