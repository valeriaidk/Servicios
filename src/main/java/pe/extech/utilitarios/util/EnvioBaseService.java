package pe.extech.utilitarios.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import pe.extech.utilitarios.domain.consumo.ConsumoRepository;
import pe.extech.utilitarios.domain.usuario.UsuarioRepository;
import pe.extech.utilitarios.exception.LimiteAlcanzadoException;

import java.util.List;
import java.util.Map;

/**
 * Lógica base compartida por SmsService y CorreoService (R4/R10).
 *
 * Cualquier cambio en validación de plan, templates o registro de consumo
 * afecta a ambos canales automáticamente.
 */
@Slf4j
@RequiredArgsConstructor
public abstract class EnvioBaseService {

    protected final ConsumoRepository consumoRepository;
    protected final PlantillaUtil plantillaUtil;
    protected final ObjectMapper objectMapper;
    protected final JdbcTemplate jdbcTemplate;
    protected final UsuarioRepository usuarioRepository;

    /**
     * Resuelve el nombre visible del usuario por su ID.
     * Se usa para:
     *   - persistir en IT_Consumo.UsuarioRegistro (VARCHAR 200)
     *   - exponer como "nombreUsuario" en la respuesta JSON
     * Devuelve null si no se encuentra (NON_NULL en el DTO lo omite del JSON).
     */
    protected String resolverNombreUsuario(int usuarioId) {
        try {
            return usuarioRepository.obtenerNombrePorId(usuarioId);
        } catch (Exception e) {
            log.warn("[BASE] No se pudo resolver nombre para usuarioId={}: {}", usuarioId, e.getMessage());
            return null;
        }
    }

    /**
     * Valida si el usuario puede consumir la función.
     * Si no puede: registra consumo fallido (R2) y lanza excepción.
     * Si puede: retorna PlanContext con (plan, consumoActual, limiteMaximo).
     *
     * Regla 9: si el SP retorna NombrePlan vacío, el usuario no tiene plan activo → bloquear.
     * consumoActual en el PlanContext es el conteo ANTES de este request.
     * Para mostrar en la respuesta, usar consumoActual + 1.
     */
    /**
     * Valida si el usuario puede consumir la función.
     * Si no puede: registra consumo fallido (R2) con el nombre del usuario y lanza excepción.
     * Si puede: retorna PlanContext con (plan, consumoActual, limiteMaximo).
     *
     * @param nombreUsuario se persiste en IT_Consumo.UsuarioRegistro incluso en error,
     *                      para que la auditoría identifique quién agotó el límite.
     */
    protected PlanContext validarPlan(int usuarioId, int funcionId, String nombreUsuario) {
        Map<String, Object> resultado = consumoRepository.validarLimitePlan(usuarioId, funcionId);

        // Regla 9: sin plan activo → bloquear aunque PuedeContinuar sea 1
        String nombrePlan = resultado.containsKey("NombrePlan")
                ? (String) resultado.get("NombrePlan") : "";
        if (nombrePlan == null || nombrePlan.isBlank()) {
            consumoRepository.registrar(usuarioId, funcionId, null,
                    "Usuario sin plan activo.", false, false, nombreUsuario);
            throw new LimiteAlcanzadoException(
                    "No tienes un plan activo. Contáctate con soporte.", 0, 0, "SIN_PLAN");
        }

        // BIT: el driver MS JDBC retorna columnas BIT como Boolean; usar ValidadorUtil.bit()
        boolean puede = ValidadorUtil.bit(resultado.get("PuedeContinuar"));
        int consumoActual = resultado.containsKey("ConsumoActual")
                ? ((Number) resultado.get("ConsumoActual")).intValue() : 0;
        Integer limiteMaximo = resultado.containsKey("LimiteMaximo") && resultado.get("LimiteMaximo") != null
                ? ((Number) resultado.get("LimiteMaximo")).intValue() : null;

        if (!puede) {
            int lim = limiteMaximo != null ? limiteMaximo : 0;
            String mensaje = resultado.containsKey("MensajeError")
                    ? (String) resultado.get("MensajeError") : "Límite de consumo alcanzado.";
            consumoRepository.registrar(usuarioId, funcionId, null, mensaje,
                    false, false, nombreUsuario);
            throw new LimiteAlcanzadoException(mensaje, consumoActual, lim, nombrePlan);
        }

        return new PlanContext(nombrePlan, consumoActual, limiteMaximo);
    }

    /**
     * Resuelve el contenido del mensaje cargando el template desde el classpath.
     *
     * Ruta: templates/{canal_lowercase}/{templateCodigo_lowercase}.html  (EMAIL)
     *       templates/{canal_lowercase}/{templateCodigo_lowercase}.txt   (SMS)
     *
     * Ejemplos:
     *   - EMAIL + OTP      → templates/correo/otp.html
     *   - SMS   + OTP      → templates/sms/otp.txt
     *   - EMAIL + BIENVENIDA → templates/correo/bienvenida.html
     *
     * El AsuntoTemplate sigue leyendo desde IT_Template en BD (via resolverAsunto()).
     * Esto permite cambiar el asunto sin redeploy, mientras el cuerpo está versionado en git.
     *
     * Si no viene templateCodigo: usa variables.get("cuerpo") (modo INLINE).
     * El parámetro version se ignora — la versión se controla con el nombre del archivo.
     */
    protected String resolverContenido(int funcionId, String canal,
                                       String templateCodigo,
                                       Map<String, Object> variables,
                                       Integer version) {
        if (templateCodigo != null && !templateCodigo.isBlank()) {
            // Extensión: EMAIL usa .html, SMS usa .txt
            String extension = "EMAIL".equalsIgnoreCase(canal) ? ".html" : ".txt";
            String carpeta   = "EMAIL".equalsIgnoreCase(canal) ? "correo" : "sms";
            String ruta      = "templates/" + carpeta + "/" + templateCodigo.toLowerCase() + extension;

            String cuerpo = plantillaUtil.cargarDesdeClasspath(ruta);
            return plantillaUtil.renderizar(cuerpo, variables);
        }
        return variables.containsKey("cuerpo") ? (String) variables.get("cuerpo") : null;
    }

    /** Sobrecarga sin versión — compatibilidad con llamadas existentes. */
    protected String resolverContenido(int funcionId, String canal,
                                       String templateCodigo,
                                       Map<String, Object> variables) {
        return resolverContenido(funcionId, canal, templateCodigo, variables, null);
    }

    /**
     * Resuelve el asunto del correo desde IT_Template (solo EMAIL).
     * Acepta versión opcional para elegir la versión exacta del template.
     */
    protected String resolverAsunto(int funcionId, String templateCodigo,
                                    Map<String, Object> variables,
                                    Integer version) {
        if (templateCodigo != null && !templateCodigo.isBlank()) {
            List<Map<String, Object>> templates;
            if (version != null) {
                templates = jdbcTemplate.queryForList(
                        "SELECT AsuntoTemplate FROM dbo.IT_Template " +
                        "WHERE ApiServicesFuncionId = ? AND Canal = 'EMAIL' AND Codigo = ? " +
                        "AND Version = ? AND Activo = 1 AND Eliminado = 0",
                        funcionId, templateCodigo, version);
            } else {
                templates = jdbcTemplate.queryForList(
                        "SELECT AsuntoTemplate FROM dbo.IT_Template " +
                        "WHERE ApiServicesFuncionId = ? AND Canal = 'EMAIL' AND Codigo = ? " +
                        "AND Activo = 1 AND Eliminado = 0 " +
                        "ORDER BY Version DESC",
                        funcionId, templateCodigo);
            }

            if (!templates.isEmpty()) {
                String asunto = (String) templates.get(0).get("AsuntoTemplate");
                return plantillaUtil.renderizar(asunto, variables);
            }
        }
        return variables.containsKey("asunto") ? (String) variables.get("asunto") : "Mensaje de Extech";
    }

    /** Sobrecarga sin versión. */
    protected String resolverAsunto(int funcionId, String templateCodigo,
                                    Map<String, Object> variables) {
        return resolverAsunto(funcionId, templateCodigo, variables, null);
    }

    /**
     * Registra el consumo en IT_Consumo con nombre del usuario
     * (R2: siempre, EsConsulta=false para SMS y Correo).
     * El nombre se persiste en IT_Consumo.UsuarioRegistro (VARCHAR 200).
     */
    protected void registrarConsumo(int usuarioId, int funcionId,
                                     String request, String response, boolean exito,
                                     String nombreUsuario) {
        consumoRepository.registrar(usuarioId, funcionId, request, response,
                exito, false, nombreUsuario);
    }

    /**
     * Sobrecarga sin nombre — para paths de error donde el nombre no está disponible.
     * Persiste NULL en IT_Consumo.UsuarioRegistro.
     */
    protected void registrarConsumo(int usuarioId, int funcionId,
                                     String request, String response, boolean exito) {
        consumoRepository.registrar(usuarioId, funcionId, request, response, exito, false);
    }

    /** Serializa objeto a JSON para guardar en IT_Consumo.Request/Response */
    protected String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            return obj != null ? obj.toString() : null;
        }
    }
}
