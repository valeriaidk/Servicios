package pe.extech.utilitarios.sms;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import pe.extech.utilitarios.domain.consumo.ConsumoRepository;
import pe.extech.utilitarios.exception.LimiteAlcanzadoException;
import pe.extech.utilitarios.exception.ServicioNoDisponibleException;
import pe.extech.utilitarios.sms.dto.SmsRequest;
import pe.extech.utilitarios.sms.dto.SmsResponse;
import pe.extech.utilitarios.util.AesUtil;
import pe.extech.utilitarios.util.EnvioBaseService;
import pe.extech.utilitarios.util.PlantillaUtil;
import pe.extech.utilitarios.util.PlanContext;
import pe.extech.utilitarios.util.ValidadorUtil;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Servicio SMS — envío vía Infobip.
 * Extiende EnvioBaseService para reutilizar lógica de plan, template y consumo (R4).
 * Solo enviarInfobip() difiere del canal Correo.
 *
 * Flujo (R2 — 1 request = 1 consumo en IT_Consumo):
 * 1. Resolver configuración del proveedor vía SP (ApiServicesFuncionId + token AES)
 * 2. Validar límite de plan (Regla 9: sin plan activo → bloquear)
 * 3. Validar teléfono localmente
 * 4. Resolver contenido (modo TEMPLATE o INLINE)
 * 5. Llamar a Infobip con token descifrado AES
 * 6. Registrar en IT_Consumo (R2: siempre, incluso si falla)
 * 7. Retornar respuesta enriquecida con contexto de plan
 */
@Slf4j
@Service
public class SmsService extends EnvioBaseService {

    private final SmsRepository smsRepository;
    private final AesUtil aesUtil;
    private final long timeoutMs;

    public SmsService(SmsRepository smsRepository,
                      ConsumoRepository consumoRepository,
                      PlantillaUtil plantillaUtil,
                      ObjectMapper objectMapper,
                      JdbcTemplate jdbcTemplate,
                      AesUtil aesUtil,
                      @Value("${extech.proveedor.infobip.timeout-ms:30000}") long timeoutMs) {
        super(consumoRepository, plantillaUtil, objectMapper, jdbcTemplate);
        this.smsRepository = smsRepository;
        this.aesUtil = aesUtil;
        this.timeoutMs = timeoutMs;
    }

    public SmsResponse enviar(int usuarioId, SmsRequest request) {
        // Resolver configuración del proveedor: ApiServicesFuncionId + endpoint + token AES
        Map<String, Object> config = smsRepository.resolverConfiguracion(usuarioId);
        int funcionId = ((Number) config.get("ApiServicesFuncionId")).intValue();
        String payload = toJson(request);

        // Validar límite de plan — retorna contexto para la respuesta
        PlanContext plan = validarPlan(usuarioId, funcionId);

        // Validar teléfono localmente — evita consumos por datos inválidos al proveedor
        ValidadorUtil.validarTelefono(request.to());

        // Resolver contenido según modo
        String contenido = resolverContenidoSms(request, funcionId);

        // Token en IT_ApiExternaFuncion.Token; NUNCA loguear el valor plano.
        // descifrarConFallback() maneja tanto tokens cifrados AES-256 (producción)
        // como tokens almacenados como texto plano (compatibilidad con datos existentes).
        String endpoint = (String) config.get("EndpointExterno");
        String autorizacion = (String) config.get("Autorizacion");
        String tokenReal = aesUtil.descifrarConFallback((String) config.get("Token")); // NUNCA loguear

        SmsResponse respuesta;
        boolean exito = false;
        String responseJson;

        try {
            String referencia = enviarInfobip(endpoint, autorizacion, tokenReal,
                    request.to(), contenido);
            // consumoActual + 1: este request acaba de registrarse
            respuesta = new SmsResponse(
                    true,
                    "OPERACION_EXITOSA",
                    "SMS enviado correctamente.",
                    usuarioId,
                    plan.plan(),
                    plan.consumoActual() + 1,
                    plan.limiteMaximo(),
                    funcionId,
                    "INFOBIP",
                    referencia
            );
            exito = true;
            responseJson = toJson(respuesta);
        } catch (LimiteAlcanzadoException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error enviando SMS a {}: {}", request.to(), e.getMessage());
            responseJson = "{\"error\": \"" + e.getMessage() + "\"}";
            // R2: registrar consumo fallido (EsConsulta=false para SMS)
            registrarConsumo(usuarioId, funcionId, payload, responseJson, false);
            throw new ServicioNoDisponibleException("Infobip-SMS");
        }

        // R2: 1 request = 1 consumo registrado en IT_Consumo
        registrarConsumo(usuarioId, funcionId, payload, responseJson, exito);
        return respuesta;
    }

    /**
     * Resuelve el contenido del SMS según el modo del request.
     * TEMPLATE: busca en IT_Template por channel+code+version.
     * INLINE: usa el campo "message" directamente.
     */
    private String resolverContenidoSms(SmsRequest request, int funcionId) {
        boolean isTemplate = "TEMPLATE".equalsIgnoreCase(request.mode());

        if (isTemplate) {
            if (request.template() == null) {
                throw new IllegalArgumentException(
                        "En modo TEMPLATE debe incluir el objeto 'template' con 'channel' y 'code'.");
            }
            Map<String, Object> vars = new HashMap<>(
                    request.variables() != null ? request.variables() : Map.of());
            return resolverContenido(
                    funcionId,
                    request.template().channel(),
                    request.template().code(),
                    vars,
                    request.template().version()
            );
        }

        // Modo INLINE
        if (request.message() == null || request.message().isBlank()) {
            throw new IllegalArgumentException(
                    "En modo INLINE debe incluir el campo 'message' con el texto del SMS.");
        }
        return request.message();
    }

    /**
     * Llama a Infobip. El header Authorization viene de IT_ApiExternaFuncion.Autorizacion
     * (ej: "App {TOKEN}") con el placeholder {TOKEN} reemplazado por el token descifrado.
     */
    private String enviarInfobip(String endpoint, String autorizacion, String token,
                                  String to, String mensaje) {
        Map<String, Object> body = Map.of(
                "messages", new Object[]{
                    Map.of("destinations", new Object[]{ Map.of("to", to) },
                           "from", "ExtechSMS",
                           "text", mensaje)
                });

        WebClient.builder()
                .baseUrl(endpoint)
                .defaultHeader(HttpHeaders.AUTHORIZATION, autorizacion.replace("{TOKEN}", token))
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build()
                .post()
                .body(BodyInserters.fromValue(body))
                .retrieve()
                .bodyToMono(Map.class)
                .timeout(Duration.ofMillis(timeoutMs))
                .block();

        return "SMS_" + System.currentTimeMillis();
    }
}
