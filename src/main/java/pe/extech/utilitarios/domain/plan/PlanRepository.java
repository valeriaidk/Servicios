package pe.extech.utilitarios.domain.plan;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

/**
 * Repositorio de planes — acceso exclusivo vía Stored Procedures.
 */
@Repository
public class PlanRepository {

    private final JdbcTemplate jdbcTemplate;

    public PlanRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Obtiene la configuración completa de un plan (funciones y límites).
     * SP: uspPlanObtenerConfiguracionCompleta(@PlanId INT)
     *
     * ATENCIÓN: El SP recibe @PlanId (no UsuarioId).
     * Columnas retornadas: PlanId, Nombre, Descripcion, PrecioMensual,
     *                      FuncionNombre, TipoLimite, Limite.
     */
    public List<Map<String, Object>> obtenerConfiguracionCompleta(int planId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "EXEC dbo.uspPlanObtenerConfiguracionCompleta ?", planId);
        if (rows.isEmpty()) {
            throw new IllegalStateException(
                    "No se encontró configuración para el plan con ID " + planId + ".");
        }
        return rows;
    }

    /**
     * Cambia el plan activo del usuario (transaccional en BD).
     * SP: uspPlanUsuarioCambiar(@UsuarioId, @NuevoPlanId, @Observacion, @UsuarioAccion)
     *
     * Columnas retornadas: PlanUsuarioId, UsuarioId, PlanId, NombrePlan,
     *                      FechaInicioVigencia, EstadoSuscripcion.
     */
    public Map<String, Object> cambiarPlan(int usuarioId, int nuevoPlanId,
                                            String observacion, int usuarioAccion) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "EXEC dbo.uspPlanUsuarioCambiar ?, ?, ?, ?",
                usuarioId, nuevoPlanId, observacion, usuarioAccion);
        if (rows.isEmpty()) {
            throw new IllegalStateException("El cambio de plan no retornó resultado.");
        }
        Map<String, Object> row = rows.get(0);
        if (!row.containsKey("PlanId") || !row.containsKey("NombrePlan")) {
            throw new IllegalStateException("El SP no retornó PlanId/NombrePlan esperados.");
        }
        return row;
    }

    /**
     * Lista todos los planes activos (para admin y selección en frontend).
     * SP: uspPlanListar — retorna PlanId, Nombre, Descripcion, PrecioMensual
     *     ordenados por PrecioMensual ASC.
     */
    public List<Map<String, Object>> listarPlanes() {
        return jdbcTemplate.queryForList("EXEC dbo.uspPlanListar");
    }
}
