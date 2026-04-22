package pe.extech.utilitarios.modules.correo.infrastructure.provider;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import pe.extech.utilitarios.exception.ServicioNoDisponibleException;
import pe.extech.utilitarios.modules.correo.domain.ports.CorreoProvider;
import pe.extech.utilitarios.modules.correo.infrastructure.client.MicrosoftGraphAuthClient;
import pe.extech.utilitarios.util.AesUtil;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Adaptador del puerto {@link CorreoProvider} para Microsoft Graph
 * (OAuth2 client_credentials).
 *
 * <p>
 * Obtiene un {@code access_token} a través de {@link MicrosoftGraphAuthClient}
 * y envía el correo con el endpoint {@code /users/{outlookUser}/sendMail}.
 * </p>
 *
 * <p>
 * {@code access_token} y {@code clientSecret} nunca se persisten ni loguean
 * (R6/R8).
 * </p>
 */
@Slf4j
@Component
@Order(1)
public class MicrosoftGraphCorreoProvider implements CorreoProvider {

    private static final String NOMBRE = "MICROSOFT_GRAPH";
    private static final String SEND_MAIL_URL_TEMPLATE =
            "https://graph.microsoft.com/v1.0/users/%s/sendMail";

    private final MicrosoftGraphAuthClient authClient;
    private final AesUtil aesUtil;
    private final String outlookUser;
    private final long timeoutMs;

    public MicrosoftGraphCorreoProvider(
            MicrosoftGraphAuthClient authClient,
            AesUtil aesUtil,
            @Value("${extech.correo.microsoft.outlook-user}") String outlookUser,
            @Value("${extech.proveedor.correo.timeout-ms:30000}") long timeoutMs) {
        this.authClient = authClient;
        this.aesUtil = aesUtil;
        this.outlookUser = outlookUser;
        this.timeoutMs = timeoutMs;
    }

    @Override
    public String enviar(Map<String, Object> config, CorreoMensaje mensaje) {

        // 1. Descifrar clientSecret (vive solo en esta invocación)
        String clientSecret = aesUtil.descifrarConFallback((String) config.get("ClientSecretCifrado"));

        // 2. Obtener access_token vía OAuth2 (vive solo en esta invocación)
        String accessToken = authClient.obtenerAccessToken(clientSecret);

        // 3. Construir payload de Graph sendMail
        List<Map<String, Object>> toRecipients = mensaje.destinatarios().stream()
                .map(addr -> {
                    Map<String, Object> emailAddress = new HashMap<>();
                    emailAddress.put("address", addr);
                    Map<String, Object> recipient = new HashMap<>();
                    recipient.put("emailAddress", emailAddress);
                    return recipient;
                })
                .toList();

        Map<String, Object> messageBody = new HashMap<>();
        messageBody.put("contentType", "HTML");
        messageBody.put("content", mensaje.cuerpoHtml());

        Map<String, Object> message = new HashMap<>();
        message.put("subject", mensaje.asunto() != null ? mensaje.asunto() : "Mensaje de Extech");
        message.put("body", messageBody);
        message.put("toRecipients", toRecipients);

        Map<String, Object> payload = new HashMap<>();
        payload.put("message", message);
        payload.put("saveToSentItems", false);

        String sendMailUrl = String.format(SEND_MAIL_URL_TEMPLATE, outlookUser);
        log.info("[CORREO] Enviando vía Graph → outlookUser={}, destinatarios={}",
                outlookUser, mensaje.destinatarios());

        try {
            WebClient.builder()
                    .build()
                    .post()
                    .uri(sendMailUrl)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .bodyValue(payload)
                    .retrieve()
                    .toBodilessEntity()
                    .timeout(Duration.ofMillis(timeoutMs))
                    .block();

        } catch (WebClientResponseException e) {
            log.error("[CORREO] Error en Graph sendMail: HTTP {} | Body: {}",
                    e.getStatusCode().value(), e.getResponseBodyAsString());
            throw new ServicioNoDisponibleException("Microsoft-Graph-Correo");
        }

        String referencia = "GRAPH_" + System.currentTimeMillis();
        log.info("[CORREO] Enviado correctamente. Referencia: {}", referencia);
        return referencia;
    }

    @Override
    public String getProveedor() {
        return NOMBRE;
    }
}
