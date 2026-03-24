package pe.extech.utilitarios.correo;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import pe.extech.utilitarios.correo.dto.CorreoRequest;
import pe.extech.utilitarios.correo.dto.CorreoResponse;
import pe.extech.utilitarios.domain.consumo.ConsumoRepository;
import pe.extech.utilitarios.exception.LimiteAlcanzadoException;
import pe.extech.utilitarios.exception.ServicioNoDisponibleException;
import pe.extech.utilitarios.util.AesUtil;
import pe.extech.utilitarios.util.EnvioBaseService;
import pe.extech.utilitarios.util.PlantillaUtil;
import pe.extech.utilitarios.util.PlanContext;
import pe.extech.utilitarios.util.ValidadorUtil;

import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Servicio Correo — envío vía Microsoft Graph (OAuth2 client_credentials).
 * Extiende EnvioBaseService: validación de plan, templates y consumo = idéntico a SmsService (R4).
 *
 * Fuentes de configuración:
 *   - clientId, tenantId, outlookUser → application.properties (@Value)
 *   - clientSecret → IT_ApiExternaFuncion.Token (cifrado AES-256, Codigo='MICROSOFT_GRAPH_CORREO')
 *
 * Flujo OAuth2 (runtime, sin persistencia):
 *   1. Descifrar clientSecret desde BD con AesUtil.descifrarConFallback()
 *   2. POST login.microsoftonline.com/{tenantId}/oauth2/v2.0/token → access_token
 *   3. POST graph.microsoft.com/v1.0/users/{outlookUser}/sendMail con Bearer access_token
 *
 * Seguridad (R6/R8):
 *   - access_token NO se persiste ni se loguea. Vive solo en memoria durante el request.
 *   - clientSecret NO se loguea. Descifrado solo en tiempo de ejecución.
 *
 * Flujo completo (R2 — 1 request = 1 consumo en IT_Consumo):
 *   1. Validar límite de plan
 *   2. Validar correo del primer destinatario localmente
 *   3. Resolver contenido y asunto (modo TEMPLATE o INLINE)
 *   4. Obtener access_token vía OAuth2 client_credentials
 *   5. Enviar vía Graph sendMail
 *   6. Registrar en IT_Consumo
 *   7. Retornar respuesta enriquecida con contexto de plan
 */
@Slf4j
@Service
public class CorreoService extends EnvioBaseService {

    private static final String GRAPH_TOKEN_TEMPLATE =
            "https://login.microsoftonline.com/%s/oauth2/v2.0/token";
    private static final String GRAPH_SEND_MAIL_TEMPLATE =
            "https://graph.microsoft.com/v1.0/users/%s/sendMail";

    private final CorreoRepository correoRepository;
    private final AesUtil aesUtil;
    private final String clientId;
    private final String tenantId;
    private final String outlookUser;
    private final long timeoutMs;

    public CorreoService(CorreoRepository correoRepository,
                         ConsumoRepository consumoRepository,
                         PlantillaUtil plantillaUtil,
                         ObjectMapper objectMapper,
                         JdbcTemplate jdbcTemplate,
                         AesUtil aesUtil,
                         @Value("${extech.correo.microsoft.client-id}") String clientId,
                         @Value("${extech.correo.microsoft.tenant-id}") String tenantId,
                         @Value("${extech.correo.microsoft.outlook-user}") String outlookUser,
                         @Value("${extech.proveedor.correo.timeout-ms:30000}") long timeoutMs) {
        super(consumoRepository, plantillaUtil, objectMapper, jdbcTemplate);
        this.correoRepository = correoRepository;
        this.aesUtil = aesUtil;
        this.clientId = clientId;
        this.tenantId = tenantId;
        this.outlookUser = outlookUser;
        this.timeoutMs = timeoutMs;
    }

    public CorreoResponse enviar(int usuarioId, CorreoRequest request) {
        int funcionId = correoRepository.obtenerFuncionId();
        String payload = toJson(request);

        // Validar límite de plan — retorna contexto para la respuesta
        PlanContext plan = validarPlan(usuarioId, funcionId);

        // Validar correo del primer destinatario localmente (§14.4)
        ValidadorUtil.validarCorreo(request.primaryTo());

        // Resolver contenido y asunto según modo (TEMPLATE o INLINE)
        String[] contenidoYAsunto = resolverContenidoCorreo(request, funcionId);
        String cuerpoHtml = contenidoYAsunto[0];
        String asunto     = contenidoYAsunto[1];

        CorreoResponse respuesta;
        boolean exito = false;
        String responseJson;

        try {
            // ── Paso 1: Obtener ClientSecret desde BD y descifrarlo en runtime ──
            // El valor plano NO se loguea (R8). Vive solo en este scope.
            String clientSecret = aesUtil.descifrarConFallback(
                    correoRepository.obtenerClientSecretCifrado());

            // ── Paso 2: OAuth2 client_credentials → access_token ──────────────
            // access_token vive solo en memoria durante este request (R6).
            String accessToken = obtenerAccessToken(clientSecret);

            // ── Paso 3: Enviar correo vía Graph sendMail ──────────────────────
            String[] recipients = request.to().toArray(String[]::new);
            String referencia = enviarGraph(accessToken, recipients, asunto, cuerpoHtml);

            // ── Paso 4: Construir data con detalle del envío ──────────────────
            boolean isTemplate = "TEMPLATE".equalsIgnoreCase(request.mode());
            CorreoResponse.CorreoData data = new CorreoResponse.CorreoData(
                    request.mode().toUpperCase(),
                    isTemplate && request.template() != null ? request.template().code()    : null,
                    isTemplate && request.template() != null ? request.template().version() : null,
                    request.to(),
                    request.to().size(),
                    "Correo enviado correctamente vía Microsoft Graph",
                    referencia
            );

            // consumoActual + 1: este request acaba de registrarse (R2)
            respuesta = new CorreoResponse(
                    true,
                    "OPERACION_EXITOSA",
                    "Correo enviado correctamente.",
                    usuarioId,
                    plan.plan(),
                    plan.consumoActual() + 1,
                    plan.limiteMaximo(),
                    funcionId,
                    "Envío de Correo",
                    "CORREO_ENVIO",
                    "Envío de correos electrónicos",
                    data
            );
            exito = true;
            responseJson = toJson(respuesta);

        } catch (LimiteAlcanzadoException e) {
            throw e;
        } catch (Exception e) {
            log.error("[CORREO] Error enviando correo a {}: {}", request.to(), e.getMessage());
            responseJson = "{\"error\": \"" + e.getMessage() + "\"}";
            registrarConsumo(usuarioId, funcionId, payload, responseJson, false);
            throw new ServicioNoDisponibleException("Microsoft-Graph-Correo");
        }

        // R2: 1 request = 1 consumo registrado en IT_Consumo
        registrarConsumo(usuarioId, funcionId, payload, responseJson, exito);
        return respuesta;
    }

    /**
     * OAuth2 Client Credentials Grant.
     * POST https://login.microsoftonline.com/{tenantId}/oauth2/v2.0/token
     *
     * Retorna el access_token en texto plano.
     * El valor NUNCA se loguea ni persiste — se usa directamente como Bearer header.
     */
    @SuppressWarnings("rawtypes")
    private String obtenerAccessToken(String clientSecret) {
        String tokenUrl = String.format(GRAPH_TOKEN_TEMPLATE, tenantId);

        // Cuerpo form-urlencoded (clientSecret no aparece en logs)
        String body = "grant_type=client_credentials"
                + "&client_id=" + clientId
                + "&client_secret=" + clientSecret
                + "&scope=https%3A%2F%2Fgraph.microsoft.com%2F.default";

        log.info("[CORREO] Solicitando access_token a Microsoft (tenantId={}, clientId={})",
                 tenantId, clientId);

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
            log.error("[CORREO] Error al obtener access_token de Microsoft: HTTP {} | Body: {}",
                      e.getStatusCode().value(), e.getResponseBodyAsString());
            throw new ServicioNoDisponibleException("Microsoft-Graph-Token");
        }

        if (tokenResponse == null || !tokenResponse.containsKey("access_token")) {
            log.error("[CORREO] Respuesta de token Microsoft sin access_token. Response: {}",
                      tokenResponse);
            throw new ServicioNoDisponibleException("Microsoft-Graph-Token");
        }

        log.info("[CORREO] access_token obtenido correctamente (no se loguea el valor).");
        return (String) tokenResponse.get("access_token");
    }

    /**
     * Envía el correo vía Microsoft Graph sendMail.
     * POST https://graph.microsoft.com/v1.0/users/{outlookUser}/sendMail
     *
     * Graph devuelve 202 Accepted sin cuerpo en caso de éxito.
     * La referencia retornada es un timestamp local para trazabilidad.
     *
     * @param accessToken  Token OAuth2 obtenido en runtime — no persistido ni logueado.
     * @param to           Arreglo de destinatarios.
     * @param asunto       Asunto del correo.
     * @param cuerpoHtml   Cuerpo HTML del mensaje.
     */
    private String enviarGraph(String accessToken, String[] to,
                                String asunto, String cuerpoHtml) {
        String sendMailUrl = String.format(GRAPH_SEND_MAIL_TEMPLATE, outlookUser);

        // Construir lista de destinatarios en formato Graph API
        List<Map<String, Object>> toRecipients = Arrays.stream(to)
                .map(addr -> {
                    Map<String, Object> emailAddress = new HashMap<>();
                    emailAddress.put("address", addr);
                    Map<String, Object> recipient = new HashMap<>();
                    recipient.put("emailAddress", emailAddress);
                    return recipient;
                })
                .collect(Collectors.toList());

        // Cuerpo del mensaje (HTML)
        Map<String, Object> messageBody = new HashMap<>();
        messageBody.put("contentType", "HTML");
        messageBody.put("content", cuerpoHtml);

        // Objeto message completo
        Map<String, Object> message = new HashMap<>();
        message.put("subject", asunto != null ? asunto : "Mensaje de Extech");
        message.put("body", messageBody);
        message.put("toRecipients", toRecipients);

        // Payload raíz de sendMail
        Map<String, Object> sendMailPayload = new HashMap<>();
        sendMailPayload.put("message", message);
        sendMailPayload.put("saveToSentItems", false);

        log.info("[CORREO] Enviando correo vía Graph sendMail → outlookUser={}, destinatarios={}",
                 outlookUser, Arrays.toString(to));

        try {
            WebClient.builder()
                    .build()
                    .post()
                    .uri(sendMailUrl)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .bodyValue(sendMailPayload)
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
        log.info("[CORREO] Correo enviado correctamente. Referencia: {}", referencia);
        return referencia;
    }

    /**
     * Resuelve cuerpoHtml y asunto según el modo del request.
     * TEMPLATE: busca en IT_Template por canal + código + versión.
     * INLINE:   usa body_html y subject directamente.
     *
     * @return String[2] — [0] = cuerpoHtml, [1] = asunto
     */
    private String[] resolverContenidoCorreo(CorreoRequest request, int funcionId) {
        boolean isTemplate = "TEMPLATE".equalsIgnoreCase(request.mode());

        if (isTemplate) {
            if (request.template() == null) {
                throw new IllegalArgumentException(
                        "En modo TEMPLATE debe incluir el objeto 'template' con 'channel' y 'code'.");
            }
            Map<String, Object> vars = new HashMap<>(
                    request.variables() != null ? request.variables() : Map.of());
            Integer version = request.template().version();
            String canal   = request.template().channel();
            String codigo  = request.template().code();

            String cuerpoHtml = resolverContenido(funcionId, canal, codigo, vars, version);
            String asunto     = resolverAsunto(funcionId, codigo, vars, version);

            if (cuerpoHtml == null || cuerpoHtml.isBlank()) {
                throw new IllegalArgumentException(
                        "El template '" + codigo + "' no tiene cuerpo HTML.");
            }
            return new String[]{ cuerpoHtml, asunto };
        }

        // Modo INLINE
        String cuerpoHtml = request.bodyHtml();
        String asunto     = request.subject();
        if (cuerpoHtml == null || cuerpoHtml.isBlank()) {
            throw new IllegalArgumentException(
                    "En modo INLINE debe incluir el campo 'body_html'.");
        }
        return new String[]{ cuerpoHtml, asunto != null ? asunto : "Mensaje de Extech" };
    }
}
