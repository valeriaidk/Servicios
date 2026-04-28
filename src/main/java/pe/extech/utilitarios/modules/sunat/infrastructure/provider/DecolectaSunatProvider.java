package pe.extech.utilitarios.modules.sunat.infrastructure.provider;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import pe.extech.utilitarios.exception.ProveedorExternoException;
import pe.extech.utilitarios.modules.sunat.domain.ports.SunatProvider;
import pe.extech.utilitarios.util.AesUtil;

import java.time.Duration;
import java.util.Map;

/**
 * Adaptador del puerto {@link SunatProvider} para el proveedor externo
 * Decolecta. Realiza un GET al endpoint configurado en BD pasando el RUC
 * como query param y un header {@code Authorization} con el token AES-256
 * descifrado en runtime.
 *
 * <p>
 * Se anota con {@code @Order(1)} para ser la primera opción en la fábrica
 * {@link SunatProviderFactory}. Cualquier otro proveedor que se agregue
 * debería usar un {@code Order} mayor.
 * </p>
 */
@Slf4j
@Component
@Order(1)
public class DecolectaSunatProvider implements SunatProvider {

    private static final String NOMBRE = "DECOLECTA";

    private final AesUtil aesUtil;
    private final long timeoutMs;

    public DecolectaSunatProvider(
            AesUtil aesUtil,
            @Value("${extech.proveedor.decolecta.timeout-ms:60000}") long timeoutMs) {
        this.aesUtil = aesUtil;
        this.timeoutMs = timeoutMs;
    }

    @Override
    @SuppressWarnings({ "rawtypes", "unchecked" })
    public Map<String, Object> consultar(Map<String, Object> config, String ruc) {

        String endpoint = (String) config.get("EndpointExterno");
        String autorizacion = (String) config.get("Autorizacion");
        String tokenReal = aesUtil.descifrarConFallback((String) config.get("Token"));

        // Validación defensiva del template Authorization
        if (autorizacion == null || !autorizacion.contains("{TOKEN}")) {
            log.error("[SUNAT] Configuración incorrecta: Autorizacion no contiene '{TOKEN}'. "
                    + "Actual='{}'. Corregir en IT_ApiExternaFuncion (Codigo='DECOLECTA_SUNAT').",
                    autorizacion);
        }

        String authHeader = autorizacion != null ? autorizacion.replace("{TOKEN}", tokenReal) : "";

        // Construye la URL respetando si ya trae query params en BD
        String urlFinal = endpoint.contains("?")
                ? endpoint + ruc
                : endpoint + "?numero=" + ruc;

        log.info("[SUNAT] Llamada externa → {} | ruc={} | authLen={}",
                urlFinal, ruc, authHeader.length());

        try {
            return WebClient.builder()
                    .baseUrl(urlFinal)
                    .defaultHeader(HttpHeaders.AUTHORIZATION, authHeader)
                    .build()
                    .get()
                    .retrieve()
                    .bodyToMono(Map.class)
                    .timeout(Duration.ofMillis(timeoutMs))
                    .block();

        } catch (WebClientResponseException e) {
            log.error("[DECOLECTA-SUNAT] HTTP {} al consultar RUC {}: {}",
                    e.getStatusCode().value(), ruc, e.getResponseBodyAsString());
            throw new ProveedorExternoException(
                    "Decolecta-SUNAT",
                    e.getStatusCode().value(),
                    e.getResponseBodyAsString());
        }
    }

    @Override
    public String getProveedor() {
        return NOMBRE;
    }
}
