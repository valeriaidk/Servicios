package pe.extech.utilitarios.modules.reniec.infrastructure.provider;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import pe.extech.utilitarios.exception.ProveedorExternoException;
import pe.extech.utilitarios.modules.reniec.domain.ports.ReniecProvider;
import pe.extech.utilitarios.util.AesUtil;

import java.time.Duration;
import java.util.Map;

@Slf4j
@Component
@Order(1)
public class DecolectaReniecProvider implements ReniecProvider {

    private final AesUtil aesUtil;
    private final long timeoutMs;

    public DecolectaReniecProvider(
            AesUtil aesUtil,
            @Value("${extech.proveedor.decolecta.timeout-ms:60000}") long timeoutMs) {
        this.aesUtil = aesUtil;
        this.timeoutMs = timeoutMs;
    }

    @Override
    @SuppressWarnings("rawtypes")
    public Map consultar(Map<String, Object> config, String dni) {

        String endpoint = (String) config.get("EndpointExterno");
        String autorizacion = (String) config.get("Autorizacion");
        String token = aesUtil.descifrarConFallback((String) config.get("Token"));
        String authHeader = autorizacion.replace("{TOKEN}", token);

        // Construye la URL respetando si ya tiene query params
        String urlFinal = endpoint.contains("?")
                ? endpoint + dni
                : endpoint + "?numero=" + dni;

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
            log.error("[DECOLECTA] HTTP {} al consultar DNI {}: {}",
                    e.getStatusCode().value(), dni, e.getResponseBodyAsString());
            throw new ProveedorExternoException(
                    "Decolecta-RENIEC",
                    e.getStatusCode().value(),
                    e.getResponseBodyAsString());
        }
    }

    @Override
    public String getProveedor() {
        return "DECOLECTA";
    }
}