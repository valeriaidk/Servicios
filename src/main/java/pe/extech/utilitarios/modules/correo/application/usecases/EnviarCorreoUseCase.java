package pe.extech.utilitarios.modules.correo.application.usecases;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import pe.extech.utilitarios.exception.LimiteAlcanzadoException;
import pe.extech.utilitarios.exception.ServicioNoDisponibleException;
import pe.extech.utilitarios.modules.correo.application.interfaces.ICorreoUseCases;
import pe.extech.utilitarios.modules.correo.domain.ports.CorreoProvider;
import pe.extech.utilitarios.modules.correo.domain.repository.CorreoConfigRepository;
import pe.extech.utilitarios.modules.correo.dto.CorreoRequest;
import pe.extech.utilitarios.modules.correo.dto.CorreoResponse;
import pe.extech.utilitarios.modules.correo.infrastructure.provider.CorreoProviderFactory;
import pe.extech.utilitarios.modules.user.domain.repository.ConsumoRepository;
import pe.extech.utilitarios.modules.user.domain.repository.UsuarioRepository;
import pe.extech.utilitarios.util.PlanContext;
import pe.extech.utilitarios.util.PlantillaUtil;
import pe.extech.utilitarios.util.ValidadorUtil;

import java.util.HashMap;
import java.util.Map;

/**
 * Caso de uso: enviar un correo electrónico.
 *
 * <p>
 * Orquesta: resolución del nombre del usuario, validación de plan, validación
 * del correo, resolución de contenido y asunto (TEMPLATE o INLINE) y envío a
 * través de {@link CorreoProviderFactory}. La autenticación OAuth2 y el envío
 * a Microsoft Graph están encapsulados en el provider.
 * </p>
 *
 * <p>
 * Regla R2: 1 request = 1 registro en {@code IT_Consumo}.
 * </p>
 */
@Slf4j
@Service
public class EnviarCorreoUseCase implements ICorreoUseCases {

    private static final String SERVICIO_NOMBRE = "Envío de Correo";
    private static final String SERVICIO_CODIGO = "CORREO_ENVIO";
    private static final String SERVICIO_DESCRIPCION = "Envío de correos electrónicos";

    private final CorreoConfigRepository correoRepository;
    private final ConsumoRepository consumoRepository;
    private final UsuarioRepository usuarioRepository;
    private final CorreoProviderFactory providerFactory;
    private final PlantillaUtil plantillaUtil;
    private final ObjectMapper objectMapper;

    public EnviarCorreoUseCase(CorreoConfigRepository correoRepository,
            ConsumoRepository consumoRepository,
            UsuarioRepository usuarioRepository,
            CorreoProviderFactory providerFactory,
            PlantillaUtil plantillaUtil,
            ObjectMapper objectMapper) {
        this.correoRepository = correoRepository;
        this.consumoRepository = consumoRepository;
        this.usuarioRepository = usuarioRepository;
        this.providerFactory = providerFactory;
        this.plantillaUtil = plantillaUtil;
        this.objectMapper = objectMapper;
    }

    @Override
    public CorreoResponse enviar(int usuarioId, CorreoRequest request) {

        // 1. Contexto del usuario y configuración
        String nombreUsuario = usuarioRepository.obtenerNombrePorId(usuarioId);
        int funcionId = correoRepository.obtenerFuncionId();
        String payload = toJson(request);

        // 2. Validación del plan
        PlanContext plan = verificarLimite(usuarioId, funcionId, payload, nombreUsuario);

        // 3. Validación local del primer destinatario
        ValidadorUtil.validarCorreo(request.primaryTo());

        // 4. Resolución del contenido y asunto (TEMPLATE o INLINE)
        Contenido contenido = resolverContenido(request, funcionId);

        // 5. Envío a través del factory (Graph u otro)
        String referencia;
        try {
            Map<String, Object> config = new HashMap<>();
            config.put("ClientSecretCifrado", correoRepository.obtenerClientSecretCifrado());
            CorreoProvider.CorreoMensaje mensaje = new CorreoProvider.CorreoMensaje(
                    request.to(), contenido.asunto(), contenido.cuerpoHtml());
            referencia = providerFactory.enviar(config, mensaje);

        } catch (LimiteAlcanzadoException e) {
            throw e;

        } catch (Exception e) {
            log.error("[CORREO] Error enviando a {}: {}", request.to(), e.getMessage(), e);
            consumoRepository.registrar(usuarioId, funcionId, payload,
                    "{\"error\":\"" + e.getMessage() + "\"}", false, false, nombreUsuario);
            throw new ServicioNoDisponibleException("Microsoft-Graph-Correo");
        }

        // 6. Armar respuesta
        boolean isTemplate = "TEMPLATE".equalsIgnoreCase(request.mode());
        CorreoResponse.CorreoData data = new CorreoResponse.CorreoData(
                request.mode().toUpperCase(),
                isTemplate && request.template() != null ? request.template().code() : null,
                isTemplate && request.template() != null ? request.template().version() : null,
                request.to(),
                request.to().size(),
                "Correo enviado correctamente vía Microsoft Graph",
                referencia);

        CorreoResponse respuesta = new CorreoResponse(
                true,
                "OPERACION_EXITOSA",
                "Correo enviado correctamente.",
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

    private record Contenido(String cuerpoHtml, String asunto) {}

    private Contenido resolverContenido(CorreoRequest request, int funcionId) {
        boolean isTemplate = "TEMPLATE".equalsIgnoreCase(request.mode());

        if (isTemplate) {
            if (request.template() == null) {
                throw new IllegalArgumentException(
                        "En modo TEMPLATE debe incluir el objeto 'template' con 'channel' y 'code'.");
            }

            Map<String, Object> vars = new HashMap<>(
                    request.variables() != null ? request.variables() : Map.of());

            String codigo = request.template().code();
            Integer version = request.template().version();

            String ruta = "templates/correo/" + codigo.toLowerCase() + ".html";
            String cuerpoRaw = plantillaUtil.cargarDesdeClasspath(ruta);
            if (cuerpoRaw == null || cuerpoRaw.isBlank()) {
                throw new IllegalArgumentException(
                        "El template '" + codigo + "' no tiene cuerpo HTML en classpath.");
            }
            String cuerpoHtml = plantillaUtil.renderizar(cuerpoRaw, vars);

            String asuntoRaw = correoRepository.obtenerAsuntoTemplate(funcionId, codigo, version);
            String asunto = (asuntoRaw != null && !asuntoRaw.isBlank())
                    ? plantillaUtil.renderizar(asuntoRaw, vars)
                    : (vars.containsKey("asunto") ? (String) vars.get("asunto") : "Mensaje de Extech");

            return new Contenido(cuerpoHtml, asunto);
        }

        // Modo INLINE
        String cuerpoHtml = request.bodyHtml();
        if (cuerpoHtml == null || cuerpoHtml.isBlank()) {
            throw new IllegalArgumentException(
                    "En modo INLINE debe incluir el campo 'body_html'.");
        }
        String asunto = request.subject() != null ? request.subject() : "Mensaje de Extech";
        return new Contenido(cuerpoHtml, asunto);
    }

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

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return obj != null ? obj.toString() : null;
        }
    }
}
