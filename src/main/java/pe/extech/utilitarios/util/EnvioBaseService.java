package pe.extech.utilitarios.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import pe.extech.utilitarios.domain.consumo.ConsumoRepository;
import pe.extech.utilitarios.exception.LimiteAlcanzadoException;

import java.util.ArrayList;
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

    /**
     * Valida si el usuario puede consumir la función.
     * Si no puede: registra consumo fallido (R2) y lanza excepción.
     * Si puede: retorna PlanContext con (plan, consumoActual, limiteMaximo).
     *
     * Regla 9: si el SP retorna NombrePlan vacío, el usuario no tiene plan activo → bloquear.
     * consumoActual en el PlanContext es el conteo ANTES de este request.
     * Para mostrar en la respuesta, usar consumoActual + 1.
     */
    protected PlanContext validarPlan(int usuarioId, int funcionId) {
        Map<String, Object> resultado = consumoRepository.validarLimitePlan(usuarioId, funcionId);

        // Regla 9: sin plan activo → bloquear aunque PuedeContinuar sea 1
        String nombrePlan = resultado.containsKey("NombrePlan")
                ? (String) resultado.get("NombrePlan") : "";
        if (nombrePlan == null || nombrePlan.isBlank()) {
            consumoRepository.registrar(usuarioId, funcionId, null,
                    "Usuario sin plan activo.", false, false);
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
            consumoRepository.registrar(usuarioId, funcionId, null, mensaje, false, false);
            throw new LimiteAlcanzadoException(mensaje, consumoActual, lim, nombrePlan);
        }

        return new PlanContext(nombrePlan, consumoActual, limiteMaximo);
    }

    /**
     * Resuelve el contenido del mensaje:
     * - Si viene templateCodigo: busca en IT_Template y renderiza variables.
     *   Si version != null, se filtra por esa versión exacta; si es null, se toma la última.
     * - Si no hay template: usa el valor de variables.get("cuerpo")
     */
    protected String resolverContenido(int funcionId, String canal,
                                       String templateCodigo,
                                       Map<String, Object> variables,
                                       Integer version) {
        if (templateCodigo != null && !templateCodigo.isBlank()) {
            List<Map<String, Object>> templates;
            if (version != null) {
                templates = jdbcTemplate.queryForList(
                        "SELECT CuerpoTemplate FROM dbo.IT_Template " +
                        "WHERE ApiServicesFuncionId = ? AND Canal = ? AND Codigo = ? " +
                        "AND Version = ? AND Activo = 1 AND Eliminado = 0",
                        funcionId, canal, templateCodigo, version);
            } else {
                templates = jdbcTemplate.queryForList(
                        "SELECT CuerpoTemplate FROM dbo.IT_Template " +
                        "WHERE ApiServicesFuncionId = ? AND Canal = ? AND Codigo = ? " +
                        "AND Activo = 1 AND Eliminado = 0 " +
                        "ORDER BY Version DESC",
                        funcionId, canal, templateCodigo);
            }

            if (templates.isEmpty()) {
                String verMsg = version != null ? " versión " + version : "";
                throw new IllegalArgumentException(
                        "Template '" + templateCodigo + "'" + verMsg +
                        " no encontrado para canal " + canal + ".");
            }
            String cuerpo = (String) templates.get(0).get("CuerpoTemplate");
            return plantillaUtil.renderizar(cuerpo, variables);
        }
        return variables.containsKey("cuerpo") ? (String) variables.get("cuerpo") : null;
    }

    /** Sobrecarga sin versión — toma siempre la última (compatibilidad). */
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
     * Registra el consumo en IT_Consumo (R2: siempre, EsConsulta=false para SMS y Correo).
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
