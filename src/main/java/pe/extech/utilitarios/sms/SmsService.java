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
import org.springframework.web.reactive.function.client.WebClientResponseException;
import pe.extech.utilitarios.domain.consumo.ConsumoRepository;
import pe.extech.utilitarios.domain.usuario.UsuarioRepository;
import pe.extech.utilitarios.exception.LimiteAlcanzadoException;
import pe.extech.utilitarios.exception.ProveedorExternoException;
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
import java.util.List;
import java.util.Map;

/**
 * Servicio SMS — envío vía Infobip.
 * Extiende EnvioBaseService para reutilizar lógica de plan, template y consumo (R4).
 *
 * Fuente de configuración del proveedor:
 *   - endpoint, token (AES-256), autorizacion → IT_ApiExternaFuncion via uspResolverApiExternaPorUsuarioYFuncion
 *   - sender-id por defecto               → application.properties (extech.proveedor.infobip.sender-id)
 *
 * Flujo (R2 — 1 request = 1 consumo en IT_Consumo):
 *   1. Resolver configuración del proveedor vía SP
 *   2. Validar límite de plan (Regla 9: sin plan activo → bloquear)
 *   3. Validar teléfono localmente (§14.4)
 *   4. Resolver contenido (modo TEMPLATE o INLINE)
 *   5. Llamar a Infobip con token descifrado AES-256
 *   6. Parsear respuesta real de Infobip (messageId, status*)
 *   7. Registrar en IT_Consumo (R2: siempre, incluso si falla)
 *   8. Retornar respuesta enriquecida con contexto de plan + datos de Infobip
 */
@Slf4j
@Service
public class SmsService extends EnvioBaseService implements ISmsService {

    private static final String SERVICIO_NOMBRE      = "Envío de SMS";
    private static final String SERVICIO_CODIGO      = "SMS_SEND";
    private static final String SERVICIO_DESCRIPCION = "Envío de mensajes de texto vía API externa";

    private final SmsRepository smsRepository;
    private final AesUtil aesUtil;
    private final long timeoutMs;
    private final String defaultSenderId;

    public SmsService(SmsRepository smsRepository,
                      ConsumoRepository consumoRepository,
                      PlantillaUtil plantillaUtil,
                      ObjectMapper objectMapper,
                      JdbcTemplate jdbcTemplate,
                      UsuarioRepository usuarioRepository,
                      AesUtil aesUtil,
                      @Value("${extech.proveedor.infobip.timeout-ms:30000}") long timeoutMs,
                      @Value("${extech.proveedor.infobip.sender-id:ExtechSMS}") String defaultSenderId) {
        super(consumoRepository, plantillaUtil, objectMapper, jdbcTemplate, usuarioRepository);
        this.smsRepository   = smsRepository;
        this.aesUtil         = aesUtil;
        this.timeoutMs       = timeoutMs;
        this.defaultSenderId = defaultSenderId;
    }

    public SmsResponse enviar(int usuarioId, SmsRequest request) {
        // Resolver nombre del usuario primero — estará disponible en TODOS los paths,
        // incluyendo errores, para que IT_Consumo.UsuarioRegistro siempre identifique quién fue.
        String nombreUsuario = resolverNombreUsuario(usuarioId);

        // Resolver configuración del proveedor: ApiServicesFuncionId + endpoint + token AES
        Map<String, Object> config = smsRepository.resolverConfiguracion(usuarioId);
        int funcionId = ((Number) config.get("ApiServicesFuncionId")).intValue();
        String payload = toJson(request);

        // Validar límite de plan — retorna contexto para la respuesta
        PlanContext plan = validarPlan(usuarioId, funcionId, nombreUsuario);

        // Validar teléfono localmente — evita consumos por datos inválidos (§14.4)
        ValidadorUtil.validarTelefono(request.to());

        // Resolver contenido según modo (TEMPLATE o INLINE)
        String contenido = resolverContenidoSms(request, funcionId);

        // Token en IT_ApiExternaFuncion.Token; NUNCA loguear el valor plano (R8).
        // descifrarConFallback() maneja tanto tokens cifrados AES-256 como texto plano legacy.
        String endpoint     = (String) config.get("EndpointExterno");
        String autorizacion = (String) config.get("Autorizacion");
        String tokenReal    = aesUtil.descifrarConFallback((String) config.get("Token"));

        // Sender ID: del request si viene, si no, el default de application.properties
        String senderId = (request.senderId() != null && !request.senderId().isBlank())
                ? request.senderId()
                : defaultSenderId;

        // ── Validación del template de Autorización ───────────────────────────
        if (autorizacion == null || !autorizacion.contains("{TOKEN}")) {
            log.error("[SMS] CONFIGURACIÓN INCORRECTA - IT_ApiExternaFuncion.Autorizacion no contiene " +
                      "el placeholder {{TOKEN}}. Valor actual: '{}'. Corregir en BD: " +
                      "UPDATE IT_ApiExternaFuncion SET Autorizacion='App {{TOKEN}}' " +
                      "WHERE Codigo='INFOBIP_SMS'", autorizacion);
        } else {
            log.debug("[SMS] Autorización configurada correctamente.");
        }

        log.info("[SMS] Enviando a {} | endpoint={} | senderId={}", request.to(), endpoint, senderId);

        SmsResponse respuesta;
        boolean exito = false;
        String responseJson;

        try {
            SmsResponse.SmsData data = enviarInfobip(
                    endpoint, autorizacion, tokenReal,
                    request.to(), contenido, senderId);

            // consumoActual + 1: este request acaba de registrarse (R2)
            respuesta = new SmsResponse(
                    true,
                    "OPERACION_EXITOSA",
                    "SMS enviado correctamente.",
                    usuarioId,
                    nombreUsuario,
                    plan.plan(),
                    plan.consumoActual() + 1,
                    plan.limiteMaximo(),
                    funcionId,
                    SERVICIO_NOMBRE,
                    SERVICIO_CODIGO,
                    SERVICIO_DESCRIPCION,
                    data
            );
            exito = true;
            responseJson = toJson(respuesta);

        } catch (LimiteAlcanzadoException e) {
            throw e;

        } catch (WebClientResponseException e) {
            String infobipBody = e.getResponseBodyAsString();
            int httpStatus = e.getStatusCode().value();
            log.error("[SMS] ERROR INFOBIP {} para to {}. URL: {} | Body: {}",
                      httpStatus, request.to(), endpoint, infobipBody);
            responseJson = "{\"httpStatus\":" + httpStatus +
                           ",\"infobipError\":" +
                           (infobipBody.isBlank() ? "\"\"" : infobipBody) + "}";
            registrarConsumo(usuarioId, funcionId, payload, responseJson, false, nombreUsuario);
            throw new ProveedorExternoException("Infobip-SMS", httpStatus, infobipBody);

        } catch (Exception e) {
            log.error("[SMS] Error inesperado enviando SMS a {}: {}", request.to(), e.getMessage());
            responseJson = "{\"error\": \"" + e.getMessage() + "\"}";
            registrarConsumo(usuarioId, funcionId, payload, responseJson, false, nombreUsuario);
            throw new ServicioNoDisponibleException("Infobip-SMS");
        }

        // R2: 1 request = 1 consumo registrado en IT_Consumo (con nombre si fue exitoso)
        registrarConsumo(usuarioId, funcionId, payload, responseJson, exito, nombreUsuario);
        return respuesta;
    }

    /**
     * Resuelve el contenido del SMS según el modo del request.
     * TEMPLATE: busca en IT_Template por channel + code + version (opcional).
     * INLINE:   usa el campo "message" directamente.
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
     * Llama a Infobip y retorna los datos del mensaje enviado.
     *
     * El header Authorization viene de IT_ApiExternaFuncion.Autorizacion
     * (ej: "App {TOKEN}") con el placeholder {TOKEN} reemplazado por el token descifrado.
     * El token NO se loguea (R8).
     *
     * Body Infobip:
     * {
     *   "messages": [{
     *     "destinations": [{"to": "+51..."}],
     *     "from": "<senderId>",
     *     "text": "<mensaje>"
     *   }]
     * }
     *
     * Response Infobip (200 OK):
     * {
     *   "messages": [{
     *     "messageId": "...", "to": "+51...",
     *     "status": { "id": 26, "name": "PENDING_ACCEPTED", "groupId": 1, "groupName": "PENDING",
     *                 "description": "Message sent to next instance" }
     *   }]
     * }
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private SmsResponse.SmsData enviarInfobip(String endpoint, String autorizacion,
                                               String token, String to,
                                               String mensaje, String senderId) {
        String authHeader = autorizacion != null ? autorizacion.replace("{TOKEN}", token) : "";

        Map<String, Object> body = Map.of(
                "messages", new Object[]{
                    Map.of(
                        "destinations", new Object[]{ Map.of("to", to) },
                        "from", senderId,
                        "text", mensaje
                    )
                });

        Map externa = WebClient.builder()
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

        return mapearRespuestaInfobip(externa, to);
    }

    /**
     * Parsea la respuesta real de Infobip y mapea los campos al SmsData de la respuesta.
     *
     * Si la respuesta es null o no contiene "messages", retorna SmsData con campos null
     * (todos omitidos en JSON por @JsonInclude NON_NULL).
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private SmsResponse.SmsData mapearRespuestaInfobip(Map externa, String toFallback) {
        if (externa == null) {
            log.warn("[SMS] Infobip devolvió respuesta null.");
            return new SmsResponse.SmsData(null, toFallback, null, null, null, null, null);
        }

        log.info("[SMS] Body completo de Infobip: {}", externa);

        List<Map<String, Object>> messages =
                (List<Map<String, Object>>) externa.get("messages");

        if (messages == null || messages.isEmpty()) {
            log.warn("[SMS] Infobip no devolvió mensajes en la respuesta.");
            return new SmsResponse.SmsData(null, toFallback, null, null, null, null, null);
        }

        Map<String, Object> msg = messages.get(0);

        String messageId = (String) msg.get("messageId");
        String toReal    = msg.containsKey("to") ? (String) msg.get("to") : toFallback;

        Map<String, Object> status = (Map<String, Object>) msg.get("status");

        if (status == null) {
            log.warn("[SMS] Infobip no devolvió 'status' en el mensaje. messageId={}", messageId);
            return new SmsResponse.SmsData(messageId, toReal, null, null, null, null, null);
        }

        Integer statusId        = status.containsKey("id")
                ? ((Number) status.get("id")).intValue() : null;
        String  statusName      = (String) status.get("name");
        Integer statusGroupId   = status.containsKey("groupId")
                ? ((Number) status.get("groupId")).intValue() : null;
        String  statusGroupName = (String) status.get("groupName");
        String  statusDesc      = (String) status.get("description");

        log.info("[SMS] Mensaje registrado → messageId='{}', to='{}', status='{}'",
                 messageId, toReal, statusName);

        return new SmsResponse.SmsData(
                messageId, toReal,
                statusId, statusName,
                statusGroupId, statusGroupName, statusDesc
        );
    }
}
