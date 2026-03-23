package pe.extech.utilitarios.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.awt.*;
import java.net.URI;

/**
 * Abre Swagger UI automáticamente en el navegador cuando el servidor arranca.
 * Solo funciona en entornos con escritorio (desarrollo local).
 * En servidores headless simplemente registra la URL en los logs.
 */
@Slf4j
@Component
public class SwaggerAutoOpen {

    private static final String SWAGGER_URL = "http://localhost:8080/swagger-ui/index.html";

    @EventListener(ApplicationReadyEvent.class)
    public void abrirSwagger() {
        log.info("=================================================");
        log.info("  Swagger UI: {}", SWAGGER_URL);
        log.info("=================================================");
        try {
            if (Desktop.isDesktopSupported()
                    && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(new URI(SWAGGER_URL));
            }
        } catch (Exception e) {
            // Entorno headless o sin soporte de escritorio — la URL ya fue logueada
        }
    }
}
