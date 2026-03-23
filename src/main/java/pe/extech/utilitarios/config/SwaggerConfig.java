package pe.extech.utilitarios.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SwaggerConfig {

    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Extech Utilitarios API")
                        .version("1.0.0")
                        .description("""
                                Plataforma de servicios Extech: RENIEC, SUNAT, SMS, Correo.

                                **Autenticación:**
                                - `/auth/**` — Público (login, registro)
                                - `/usuario/**` y `/admin/**` — Requieren JWT (`Authorization: Bearer {token}`)
                                - `/servicios/**` — Requieren API Key (`X-API-Key: {apikey}`) — **JWT no válido aquí**
                                """)
                        .contact(new Contact()
                                .name("Extech")
                                .email("soporte@extech.pe")))
                .components(new Components()
                        .addSecuritySchemes("bearerAuth",
                                new SecurityScheme()
                                        .type(SecurityScheme.Type.HTTP)
                                        .scheme("bearer")
                                        .bearerFormat("JWT")
                                        .description("JWT para /usuario/** y /admin/**"))
                        .addSecuritySchemes("apiKeyAuth",
                                new SecurityScheme()
                                        .type(SecurityScheme.Type.APIKEY)
                                        .in(SecurityScheme.In.HEADER)
                                        .name("X-API-Key")
                                        .description("API Key para /servicios/**")));
    }
}
