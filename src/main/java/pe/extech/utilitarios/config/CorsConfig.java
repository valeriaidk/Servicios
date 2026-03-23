package pe.extech.utilitarios.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * Configuración CORS.
 *
 * Se expone como CorsConfigurationSource (no CorsFilter) para que Spring Security
 * la integre directamente en su cadena de filtros mediante http.cors(...).
 * De este modo el preflight OPTIONS es respondido con los headers correctos
 * ANTES de que llegue a JwtFilter o ApiKeyFilter, evitando el error CORS en el navegador.
 */
@Configuration
public class CorsConfig {

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();

        // allowCredentials=true es necesario para que el navegador acepte cookies/headers auth
        config.setAllowCredentials(true);

        // Orígenes permitidos: frontend local en desarrollo + dominios Extech en producción
        config.setAllowedOriginPatterns(List.of(
                "http://localhost:3000",
                "http://localhost:*",
                "https://*.extech.pe"
        ));

        // Headers que el frontend puede enviar (incluye X-API-Key y Authorization)
        config.setAllowedHeaders(List.of("*"));

        // Métodos HTTP permitidos — OPTIONS es obligatorio para el preflight
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH"));

        // Headers que el frontend puede leer en la respuesta
        config.setExposedHeaders(List.of("Authorization", "X-API-Key", "Content-Type"));

        // Duración del caché del preflight en segundos (1 hora)
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        // Aplicar a todas las rutas (incluye /swagger-ui, /v3/api-docs, etc.)
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
