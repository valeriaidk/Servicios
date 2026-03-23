package pe.extech.utilitarios.sunat;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.reactive.function.client.WebClient;
import pe.extech.utilitarios.domain.consumo.ConsumoRepository;
import pe.extech.utilitarios.exception.LimiteAlcanzadoException;
import pe.extech.utilitarios.exception.ProveedorExternoException;
import pe.extech.utilitarios.exception.ServicioNoDisponibleException;
import pe.extech.utilitarios.sunat.dto.SunatRequest;
import pe.extech.utilitarios.sunat.dto.SunatResponse;
import pe.extech.utilitarios.util.AesUtil;
import pe.extech.utilitarios.util.PlanContext;
import pe.extech.utilitarios.util.ValidadorUtil;

import java.time.Duration;
import java.util.Map;

/**
 * Servicio SUNAT — consulta de contribuyentes por RUC vía Decolecta.
 *
 * Flujo (R2 — 1 request = 1 consumo en IT_Consumo):
 * 1. Resolver configuración del proveedor vía SP (ApiServicesFuncionId + token AES)
 * 2. Validar límite de plan (Regla 9: sin plan activo → bloquear)
 * 3. Validar RUC localmente (evita cobros por datos inválidos al proveedor)
 * 4. Llamar a Decolecta con token descifrado AES
 * 5. Registrar en IT_Consumo (R2: siempre, incluso si falla)
 * 6. Retornar respuesta enriquecida con contexto de plan
 */
@Slf4j
@Service
public class SunatService {

    private final SunatRepository sunatRepository;
    private final ConsumoRepository consumoRepository;
    private final AesUtil aesUtil;
    private final ObjectMapper objectMapper;
    private final long timeoutMs;

    public SunatService(SunatRepository sunatRepository,
                        ConsumoRepository consumoRepository,
                        AesUtil aesUtil,
                        ObjectMapper objectMapper,
                        @Value("${extech.proveedor.decolecta.timeout-ms:60000}") long timeoutMs) {
        this.sunatRepository = sunatRepository;
        this.consumoRepository = consumoRepository;
        this.aesUtil = aesUtil;
        this.objectMapper = objectMapper;
        this.timeoutMs = timeoutMs;
    }

    public SunatResponse consultarRuc(int usuarioId, SunatRequest request) {
        // Resolver configuración del proveedor: ApiServicesFuncionId + endpoint + token AES
        Map<String, Object> config = sunatRepository.resolverConfiguracion(usuarioId);
        int funcionId = ((Number) config.get("ApiServicesFuncionId")).intValue();
        String payload = toJson(request);

        // Validar límite de plan (Reglas 6 y 9) — retorna contexto para la respuesta
        PlanContext plan = verificarLimite(usuarioId, funcionId, payload);

        // Extraer RUC del identificador PE:RUC
        String ruc = request.ruc();
        if (ruc == null || ruc.isBlank()) {
            throw new IllegalArgumentException(
                    "No se encontró identificador con scheme 'PE:RUC' en 'identifiers'.");
        }

        // Validar RUC localmente — evita consumos por datos inválidos al proveedor
        ValidadorUtil.validarRuc(ruc);

        // Token en IT_ApiExternaFuncion.Token; NUNCA loguear el valor plano.
        // descifrarConFallback() maneja tanto tokens cifrados AES-256 (producción)
        // como tokens almacenados como texto plano (compatibilidad con datos existentes).
        String endpoint     = (String) config.get("EndpointExterno");
        String autorizacion = (String) config.get("Autorizacion");
        String tokenReal    = aesUtil.descifrarConFallback((String) config.get("Token")); // NUNCA loguear

        // ── Validación del template de Autorización ───────────────────────────
        if (autorizacion == null || !autorizacion.contains("{TOKEN}")) {
            log.warn("[SUNAT] IT_ApiExternaFuncion.Autorizacion no contiene el placeholder " +
                     "{{TOKEN}}. Valor actual: '{}'. El header Authorization se enviará sin token " +
                     "→ Decolecta responderá 401. Corregir en BD: UPDATE IT_ApiExternaFuncion " +
                     "SET Autorizacion='Bearer {{TOKEN}}' WHERE Codigo='DECOLECTA_SUNAT'",
                     autorizacion);
        }
        String authHeader = autorizacion != null ? autorizacion.replace("{TOKEN}", tokenReal) : "";
        String authScheme = authHeader.contains(" ")
                            ? authHeader.substring(0, authHeader.indexOf(' '))
                            : authHeader;

        // ── Construcción de la URL final ──────────────────────────────────────
        // Igual que en RENIEC: si el endpoint ya incluye el nombre del parámetro
        // (ej: ?ruc=), solo concatenar el valor; si no, añadir el parámetro estándar.
        final String urlFinal;
        if (endpoint.contains("?")) {
            urlFinal = endpoint + ruc;
        } else {
            urlFinal = endpoint + "?ruc=" + ruc;
        }
        log.info("[SUNAT] → {} | authScheme={} | ruc={}", urlFinal, authScheme, ruc);

        SunatResponse respuesta;
        boolean exito = false;
        String responseJson;

        try {
            @SuppressWarnings("rawtypes")
            Map externa = WebClient.builder()
                    .baseUrl(urlFinal)
                    .defaultHeader(HttpHeaders.AUTHORIZATION, authHeader)
                    .build()
                    .get()
                    .retrieve()
                    .bodyToMono(Map.class)
                    .timeout(Duration.ofMillis(timeoutMs))
                    .block();

            respuesta = mapearRespuesta(externa, ruc, usuarioId, plan, funcionId);
            exito = true;
            responseJson = toJson(respuesta);

        } catch (LimiteAlcanzadoException e) {
            throw e;

        } catch (WebClientResponseException e) {
            String decolectaBody = e.getResponseBodyAsString();
            int httpStatus = e.getStatusCode().value();
            log.error("[SUNAT] Decolecta respondió {} para RUC {}. " +
                      "URL llamada: {} | authScheme: {} | Body de Decolecta: {}",
                      httpStatus, ruc, urlFinal, authScheme, decolectaBody);
            responseJson = "{\"httpStatus\":" + httpStatus +
                           ",\"decolectaError\":" + (decolectaBody.isBlank() ? "\"\"" : decolectaBody) + "}";
            consumoRepository.registrar(usuarioId, funcionId, payload, responseJson, false, true);
            throw new ProveedorExternoException("Decolecta-SUNAT", httpStatus, decolectaBody);

        } catch (Exception e) {
            log.error("[SUNAT] Error inesperado consultando RUC {}: {}", ruc, e.getMessage());
            responseJson = "{\"error\": \"" + e.getMessage() + "\"}";
            // R2: registrar consumo fallido
            consumoRepository.registrar(usuarioId, funcionId, payload, responseJson, false, true);
            throw new ServicioNoDisponibleException("Decolecta-SUNAT");
        }

        // R2: 1 request = 1 consumo registrado en IT_Consumo
        consumoRepository.registrar(usuarioId, funcionId, payload, responseJson, exito, true);
        return respuesta;
    }

    /**
     * Valida límite de plan antes de consumir el proveedor.
     * Regla 6: si falla, igual registra el consumo con Exito=0.
     * Regla 9: sin plan activo → bloquear.
     */
    private PlanContext verificarLimite(int usuarioId, int funcionId, String payload) {
        Map<String, Object> resultado = consumoRepository.validarLimitePlan(usuarioId, funcionId);

        // Regla 9: NombrePlan vacío = sin plan activo
        String nombrePlan = resultado.containsKey("NombrePlan")
                ? (String) resultado.get("NombrePlan") : "";
        if (nombrePlan == null || nombrePlan.isBlank()) {
            consumoRepository.registrar(usuarioId, funcionId, payload,
                    "Usuario sin plan activo.", false, true);
            throw new LimiteAlcanzadoException(
                    "No tienes un plan activo. Contáctate con soporte.", 0, 0, "SIN_PLAN");
        }

        int consumoActual = resultado.containsKey("ConsumoActual")
                ? ((Number) resultado.get("ConsumoActual")).intValue() : 0;
        Integer limiteMaximo = resultado.containsKey("LimiteMaximo") && resultado.get("LimiteMaximo") != null
                ? ((Number) resultado.get("LimiteMaximo")).intValue() : null;

        // BIT: PuedeContinuar viene como Boolean del driver MS JDBC
        if (!ValidadorUtil.bit(resultado.get("PuedeContinuar"))) {
            int lim = limiteMaximo != null ? limiteMaximo : 0;
            String msg = resultado.containsKey("MensajeError")
                    ? (String) resultado.get("MensajeError") : "Límite alcanzado.";
            consumoRepository.registrar(usuarioId, funcionId, payload, msg, false, true);
            throw new LimiteAlcanzadoException(msg, consumoActual, lim, nombrePlan);
        }

        return new PlanContext(nombrePlan, consumoActual, limiteMaximo);
    }

    @SuppressWarnings("rawtypes")
    private SunatResponse mapearRespuesta(Map externa, String ruc,
                                          int usuarioId, PlanContext plan, int funcionId) {
        if (externa == null) throw new ServicioNoDisponibleException("Decolecta-SUNAT");
        // consumoActual + 1: este request acaba de registrarse
        return new SunatResponse(
                true,
                "OPERACION_EXITOSA",
                "Consulta realizada correctamente.",
                usuarioId,
                plan.plan(),
                plan.consumoActual() + 1,
                plan.limiteMaximo(),
                funcionId,
                new SunatResponse.SunatData(
                        ruc,
                        (String) externa.getOrDefault("razonSocial", ""),
                        (String) externa.getOrDefault("estado", ""),
                        (String) externa.getOrDefault("condicion", ""),
                        (String) externa.getOrDefault("direccion", "")
                )
        );
    }

    private String toJson(Object obj) {
        try { return objectMapper.writeValueAsString(obj); }
        catch (Exception e) { return obj != null ? obj.toString() : null; }
    }
}
