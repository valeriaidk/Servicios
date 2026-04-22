package pe.extech.utilitarios.modules.sms.application.usecases;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import pe.extech.utilitarios.exception.LimiteAlcanzadoException;
import pe.extech.utilitarios.exception.ProveedorExternoException;
import pe.extech.utilitarios.exception.ServicioNoDisponibleException;
import pe.extech.utilitarios.modules.sms.application.interfaces.ISmsUseCases;
import pe.extech.utilitarios.modules.sms.domain.ports.SmsProvider;
import pe.extech.utilitarios.modules.sms.domain.repository.SmsConfigRepository;
import pe.extech.utilitarios.modules.sms.dto.SmsRequest;
import pe.extech.utilitarios.modules.sms.dto.SmsResponse;
import pe.extech.utilitarios.modules.sms.infrastructure.mapper.InfobipSmsMapper;
import pe.extech.utilitarios.modules.sms.infrastructure.provider.SmsProviderFactory;
import pe.extech.utilitarios.modules.user.domain.repository.ConsumoRepository;
import pe.extech.utilitarios.modules.user.domain.repository.UsuarioRepository;
import pe.extech.utilitarios.util.PlanContext;
import pe.extech.utilitarios.util.PlantillaUtil;
import pe.extech.utilitarios.util.ValidadorUtil;

import java.util.HashMap;
import java.util.Map;

/**
 * Caso de uso: enviar un SMS.
 *
 * <p>
 * Orquesta: resolución de nombre de usuario, configuración del proveedor,
 * validación del plan, validación del teléfono, resolución del contenido
 * (INLINE o TEMPLATE) y envío a través de {@link SmsProviderFactory}. El
 * mapeo específico se delega a {@link InfobipSmsMapper}.
 * </p>
 *
 * <p>
 * Regla R2: 1 request = 1 registro en {@code IT_Consumo}.
 * </p>
 */
@Slf4j
@Service
public class EnviarSmsUseCase implements ISmsUseCases {

    private static final String SERVICIO_NOMBRE = "Envío de SMS";
    private static final String SERVICIO_CODIGO = "SMS_SEND";
    private static final String SERVICIO_DESCRIPCION = "Envío de mensajes de texto vía API externa";

    private final SmsConfigRepository smsRepository;
    private final ConsumoRepository consumoRepository;
    private final UsuarioRepository usuarioRepository;
    private final SmsProviderFactory providerFactory;
    private final InfobipSmsMapper mapper;
    private final PlantillaUtil plantillaUtil;
    private final ObjectMapper objectMapper;
    private final String defaultSenderId;

    public EnviarSmsUseCase(SmsConfigRepository smsRepository,
            ConsumoRepository consumoRepository,
            UsuarioRepository usuarioRepository,
            SmsProviderFactory providerFactory,
            InfobipSmsMapper mapper,
            PlantillaUtil plantillaUtil,
            ObjectMapper objectMapper,
            @Value("${extech.proveedor.infobip.sender-id:ExtechSMS}") String defaultSenderId) {
        this.smsRepository = smsRepository;
        this.consumoRepository = consumoRepository;
        this.usuarioRepository = usuarioRepository;
        this.providerFactory = providerFactory;
        this.mapper = mapper;
        this.plantillaUtil = plantillaUtil;
        this.objectMapper = objectMapper;
        this.defaultSenderId = defaultSenderId;
    }

    @Override
    public SmsResponse enviar(int usuarioId, SmsRequest request) {

        // 1. Contexto del usuario y configuración del proveedor
        String nombreUsuario = usuarioRepository.obtenerNombrePorId(usuarioId);
        Map<String, Object> config = smsRepository.resolverConfiguracion(usuarioId);
        int funcionId = ((Number) config.get("ApiServicesFuncionId")).intValue();
        String payload = toJson(request);

        // 2. Validación del plan (consume registro si falla el límite)
        PlanContext plan = verificarLimite(usuarioId, funcionId, payload, nombreUsuario);

        // 3. Validación local del teléfono
        ValidadorUtil.validarTelefono(request.to());

        // 4. Resolución del contenido según el modo
        String contenido = resolverContenido(request);

        // 5. senderId (del request o el default)
        String senderId = (request.senderId() != null && !request.senderId().isBlank())
                ? request.senderId()
                : defaultSenderId;

        // 6. Envío a través de la fábrica de proveedores
        SmsProvider.SmsMensaje mensaje = new SmsProvider.SmsMensaje(request.to(), contenido, senderId);
        Map<String, Object> externa;
        try {
            externa = providerFactory.enviar(config, mensaje);

        } catch (LimiteAlcanzadoException e) {
            throw e;

        } catch (ProveedorExternoException e) {
            String responseJson = "{\"httpStatus\":" + e.getStatusProveedor()
                    + ",\"providerBody\":" + safeJson(e.getBodyProveedor()) + "}";
            consumoRepository.registrar(usuarioId, funcionId, payload, responseJson,
                    false, false, nombreUsuario);
            throw e;

        } catch (Exception e) {
            log.error("[SMS] Error inesperado enviando SMS a {}: {}", request.to(), e.getMessage(), e);
            consumoRepository.registrar(usuarioId, funcionId, payload,
                    "{\"error\":\"" + e.getMessage() + "\"}", false, false, nombreUsuario);
            throw new ServicioNoDisponibleException("Infobip-SMS");
        }

        // 7. Mapeo a DTO y registro de consumo exitoso
        SmsResponse.SmsData data = mapper.mapear(externa, request.to());
        SmsResponse respuesta = new SmsResponse(
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
                data);

        consumoRepository.registrar(usuarioId, funcionId, payload, toJson(respuesta),
                true, false, nombreUsuario);

        return respuesta;
    }

    // ------------------------------------------------------------------------
    // Helpers privados
    // ------------------------------------------------------------------------

    private PlanContext verificarLimite(int usuarioId, int funcionId,
            String payload, String nombreUsuario) {

        Map<String, Object> resultado = consumoRepository.validarLimitePlan(usuarioId, funcionId);

        String nombrePlan = (String) resultado.getOrDefault("NombrePlan", "");
        if (nombrePlan == null || nombrePlan.isBlank()) {
            consumoRepository.registrar(usuarioId, funcionId, payload,
                    "Usuario sin plan activo.", false, false, nombreUsuario);
            throw new LimiteAlcanzadoException(
                    "No tienes un plan activo. Contáctate con soporte.", 0, 0, "SIN_PLAN");
        }

        int consumoActual = resultado.get("ConsumoActual") != null
                ? ((Number) resultado.get("ConsumoActual")).intValue()
                : 0;
        Integer limiteMaximo = resultado.get("LimiteMaximo") != null
                ? ((Number) resultado.get("LimiteMaximo")).intValue()
                : null;

        if (!ValidadorUtil.bit(resultado.get("PuedeContinuar"))) {
            int lim = limiteMaximo != null ? limiteMaximo : 0;
            String msg = (String) resultado.getOrDefault("MensajeError", "Límite alcanzado.");
            consumoRepository.registrar(usuarioId, funcionId, payload, msg,
                    false, false, nombreUsuario);
            throw new LimiteAlcanzadoException(msg, consumoActual, lim, nombrePlan);
        }

        return new PlanContext(nombrePlan, consumoActual, limiteMaximo);
    }

    /**
     * Resuelve el contenido del SMS según el modo del request.
     * <ul>
     * <li>TEMPLATE: carga el .txt desde classpath y sustituye variables.</li>
     * <li>INLINE: usa {@code request.message()} directamente.</li>
     * </ul>
     */
    private String resolverContenido(SmsRequest request) {
        boolean isTemplate = "TEMPLATE".equalsIgnoreCase(request.mode());

        if (isTemplate) {
            if (request.template() == null) {
                throw new IllegalArgumentException(
                        "En modo TEMPLATE debe incluir el objeto 'template' con 'channel' y 'code'.");
            }
            Map<String, Object> vars = new HashMap<>(
                    request.variables() != null ? request.variables() : Map.of());

            String ruta = "templates/sms/" + request.template().code().toLowerCase() + ".txt";
            String cuerpo = plantillaUtil.cargarDesdeClasspath(ruta);
            return plantillaUtil.renderizar(cuerpo, vars);
        }

        if (request.message() == null || request.message().isBlank()) {
            throw new IllegalArgumentException(
                    "En modo INLINE debe incluir el campo 'message' con el texto del SMS.");
        }
        return request.message();
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return obj != null ? obj.toString() : null;
        }
    }

    private String safeJson(String rawBody) {
        if (rawBody == null || rawBody.isBlank()) {
            return "\"\"";
        }
        String trimmed = rawBody.trim();
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            return trimmed;
        }
        try {
            return objectMapper.writeValueAsString(rawBody);
        } catch (Exception e) {
            return "\"\"";
        }
    }
}
