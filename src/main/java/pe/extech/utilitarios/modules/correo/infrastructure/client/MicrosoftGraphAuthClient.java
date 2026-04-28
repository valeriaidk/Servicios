package pe.extech.utilitarios.modules.correo.infrastructure.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import pe.extech.utilitarios.exception.ServicioNoDisponibleException;

import java.time.Duration;
import java.util.Map;

/**
 * Cliente para obtener un {@code access_token} de Microsoft Graph vía OAuth2
 * Client Credentials Grant.
 *
 * <p>
 * El token obtenido vive sólo en memoria durante el request — no se persiste
 * ni se loguea. El {@code clientSecret} tampoco aparece en logs.
 * </p>
 *
 * <p>
 * Se extrajo del antiguo {@code CorreoService} para aislar la responsabilidad
 * de autenticación con Microsoft y permitir reutilización si en el futuro
 * aparecen otros servicios de Graph (calendario, OneDrive, etc.).
 * </p>
 */
@Slf4j
@Component
public class MicrosoftGraphAuthClient {

    private static final String TOKEN_URL_TEMPLATE =
            "https://login.microsoftonline.com/%s/oauth2/v2.0/token";

    private final String clientId;
    private final String tenantId;
    private final long timeoutMs;

    public MicrosoftGraphAuthClient(
            @Value("${extech.correo.microsoft.client-id}") String clientId,
            @Value("${extech.correo.microsoft.tenant-id}") String tenantId,
            @Value("${extech.proveedor.correo.timeout-ms:30000}") long timeoutMs) {
        this.clientId = clientId;
        this.tenantId = tenantId;
        this.timeoutMs = timeoutMs;
    }

    /**
     * Solicita un {@code access_token} al endpoint de tokens de Microsoft.
     *
     * @param clientSecret secret ya descifrado (no se loguea).
     * @return access_token listo para usar como {@code Bearer}.
     * @throws ServicioNoDisponibleException si Microsoft no responde o la
     *         respuesta es inválida.
     */
    @SuppressWarnings("rawtypes")
    public String obtenerAccessToken(String clientSecret) {

        String tokenUrl = String.format(TOKEN_URL_TEMPLATE, tenantId);

        String body = "grant_type=client_credentials"
                + "&client_id=" + clientId
                + "&client_secret=" + clientSecret
                + "&scope=https%3A%2F%2Fgraph.microsoft.com%2F.default";

        log.info("[CORREO] Solicitando access_token (tenantId={}, clientId={})", tenantId, clientId);

        Map tokenResponse;
        try {
            tokenResponse = WebClient.builder()
                    .build()
                    .post()
                    .uri(tokenUrl)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_FORM_URLENCODED_VALUE)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .timeout(Duration.ofMillis(timeoutMs))
                    .block();
        } catch (WebClientResponseException e) {
            log.error("[CORREO] Error al obtener access_token: HTTP {} | Body: {}",
                    e.getStatusCode().value(), e.getResponseBodyAsString());
            throw new ServicioNoDisponibleException("Microsoft-Graph-Token");
        }

        if (tokenResponse == null || !tokenResponse.containsKey("access_token")) {
            log.error("[CORREO] Respuesta de token sin access_token. Response: {}", tokenResponse);
            throw new ServicioNoDisponibleException("Microsoft-Graph-Token");
        }

        log.info("[CORREO] access_token obtenido correctamente.");
        return (String) tokenResponse.get("access_token");
    }
}
