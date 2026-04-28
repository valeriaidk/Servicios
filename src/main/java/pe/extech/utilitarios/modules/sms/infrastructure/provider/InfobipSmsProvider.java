package pe.extech.utilitarios.modules.sms.infrastructure.provider;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import pe.extech.utilitarios.exception.ProveedorExternoException;
import pe.extech.utilitarios.modules.sms.domain.ports.SmsProvider;
import pe.extech.utilitarios.util.AesUtil;

import java.time.Duration;
import java.util.Map;

/**
 * Adaptador del puerto {@link SmsProvider} para el proveedor externo Infobip.
 * Realiza un POST al endpoint configurado en BD con un body JSON que contiene
 * destinatario, remitente y contenido.
 *
 * <p>
 * Se anota con {@code @Order(1)} para ser la primera opción en la fábrica
 * {@link SmsProviderFactory}. Cualquier otro proveedor que se agregue debe
 * usar un {@code Order} mayor.
 * </p>
 */
@Slf4j
@Component
@Order(1)
public class InfobipSmsProvider implements SmsProvider {

    private static final String NOMBRE = "INFOBIP";

    private final AesUtil aesUtil;
    private final long timeoutMs;

    public InfobipSmsProvider(
            AesUtil aesUtil,
            @Value("${extech.proveedor.infobip.timeout-ms:30000}") long timeoutMs) {
        this.aesUtil = aesUtil;
        this.timeoutMs = timeoutMs;
    }

    @Override
    @SuppressWarnings({ "rawtypes", "unchecked" })
    public Map<String, Object> enviar(Map<String, Object> config, SmsMensaje mensaje) {

        String endpoint = (String) config.get("EndpointExterno");
        String autorizacion = (String) config.get("Autorizacion");
        String tokenReal = aesUtil.descifrarConFallback((String) config.get("Token"));

        if (autorizacion == null || !autorizacion.contains("{TOKEN}")) {
            log.error("[SMS] Configuración incorrecta: Autorizacion no contiene '{TOKEN}'. "
                    + "Actual='{}'. Corregir en IT_ApiExternaFuncion (Codigo='INFOBIP_SMS').",
                    autorizacion);
        }
        String authHeader = autorizacion != null ? autorizacion.replace("{TOKEN}", tokenReal) : "";

        Map<String, Object> body = Map.of(
                "messages", new Object[] {
                        Map.of(
                                "destinations", new Object[] { Map.of("to", mensaje.to()) },
                                "from", mensaje.senderId(),
                                "text", mensaje.contenido())
                });

        log.info("[SMS] Envío externo → {} | to={} | senderId={} | authLen={}",
                endpoint, mensaje.to(), mensaje.senderId(), authHeader.length());

        try {
            return WebClient.builder()
                    .baseUrl(endpoint)
                    .defaultHeader(HttpHeaders.AUTHORIZATION, authHeader)
                    .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .build()
                    .post()
                    .body(BodyInserters.fromValue(body))
                    .retrieve()
                    .bodyToMono(Map.class)
                    .timeout(Duration.ofMillis(timeoutMs))
                    .block();

        } catch (WebClientResponseException e) {
            log.error("[INFOBIP-SMS] HTTP {} al enviar a {}: {}",
                    e.getStatusCode().value(), mensaje.to(), e.getResponseBodyAsString());
            throw new ProveedorExternoException(
                    "Infobip-SMS",
                    e.getStatusCode().value(),
                    e.getResponseBodyAsString());
        }
    }

    @Override
    public String getProveedor() {
        return NOMBRE;
    }
}
