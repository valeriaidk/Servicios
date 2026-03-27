package pe.extech.utilitarios.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static io.swagger.v3.oas.models.security.SecurityScheme.In.HEADER;

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
                                - `/usuario/**` y `/admin/**` — Requieren `Authorization: Bearer {jwt}` en el header
                                - `/servicios/**` — Requieren `X-API-Key: {apikey}` en el header
                                """)
                        .contact(new Contact()
                                .name("Extech")
                                .email("soporte@extech.pe")))
                .components(new Components()
                        .addSecuritySchemes("apiKeyAuth",
                                new SecurityScheme()
                                        .type(SecurityScheme.Type.APIKEY)
                                        .in(HEADER)
                                        .name("X-API-Key")
                                        .description("API Key para /servicios/**")));
    }
}
