package pe.extech.utilitarios.domain.plan;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

/**
 * Repositorio de planes ({@code IT_Plan} / {@code IT_PlanUsuario}) — acceso exclusivo
 * vía Stored Procedures.
 *
 * <p>Centraliza las operaciones sobre planes: obtener configuración, cambiar el plan
 * activo de un usuario y listar los planes disponibles. Usado por
 * {@link pe.extech.utilitarios.auth.AuthService} (cambio de plan) y por el admin
 * (listado de planes).</p>
 *
 * <p><b>Patrón uniforme:</b> {@code JdbcTemplate.queryForList("EXEC ...")} en lugar
 * de {@code SimpleJdbcCall}.</p>
 */
@Repository
public class PlanRepository {

    private final JdbcTemplate jdbcTemplate;

    public PlanRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Obtiene la configuración completa de un plan: funciones habilitadas y sus límites.
     *
     * <p><b>SP ejecutado:</b> {@code uspPlanObtenerConfiguracionCompleta(@PlanId)}</p>
     *
     * <p><b>ATENCIÓN:</b> el parámetro es {@code @PlanId}, no {@code @UsuarioId}.
     * El llamador debe resolver primero el plan activo del usuario y luego pasar
     * ese {@code PlanId} a este método.</p>
     *
     * <p><b>Qué hace el SP:</b> une {@code IT_Plan} con {@code IT_PlanFuncionLimite}
     * y {@code IT_ApiServicesFuncion} para retornar una fila por cada función+límite
     * configurada para ese plan. Si el plan no tiene filas en {@code IT_PlanFuncionLimite}
     * (ej: ENTERPRISE), retorna vacío — lo que en el sistema significa "sin restricciones".</p>
     *
     * <p><b>Columnas retornadas por fila:</b> {@code PlanId}, {@code Nombre},
     * {@code Descripcion}, {@code PrecioMensual}, {@code FuncionNombre},
     * {@code TipoLimite} (MENSUAL/DIARIO/TOTAL), {@code Limite} (cantidad máxima).</p>
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
     * Cambia el plan activo del usuario en una transacción atómica.
     *
     * <p><b>SP ejecutado:</b> {@code uspPlanUsuarioCambiar(@UsuarioId, @NuevoPlanId,
     * @Observacion, @UsuarioAccion)}</p>
     *
     * <p><b>Qué hace el SP:</b> dentro de una transacción ({@code BEGIN TRAN / COMMIT}),
     * cierra el plan actual del usuario ({@code SET Activo=0, EstadoSuscripcion='CANCELADO'})
     * e inserta un nuevo registro en {@code IT_PlanUsuario} con el nuevo plan
     * ({@code Activo=1, EstadoSuscripcion='ACTIVO', FechaInicioVigencia=GETDATE()}).
     * Si el nuevo plan no existe o está inactivo, el SP lanza un error y hace
     * {@code ROLLBACK} automáticamente.</p>
     *
     * <p><b>Por qué es transaccional en BD:</b> si el UPDATE del plan anterior y el INSERT
     * del nuevo se ejecutaran por separado desde Java, un fallo intermedio dejaría al
     * usuario sin plan activo. La transacción en el SP garantiza atomicidad.</p>
     *
     * <p><b>Columnas retornadas:</b> {@code PlanUsuarioId}, {@code UsuarioId},
     * {@code PlanId}, {@code NombrePlan}, {@code FechaInicioVigencia},
     * {@code EstadoSuscripcion} — datos del nuevo plan recién activado.</p>
     *
     * @param observacion motivo del cambio (visible en {@code IT_PlanUsuario.Observacion})
     * @param usuarioAccion ID del usuario que solicita el cambio (puede ser él mismo o un admin)
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
     * Lista todos los planes activos disponibles en el sistema.
     *
     * <p><b>SP ejecutado:</b> {@code uspPlanListar} (sin parámetros)</p>
     *
     * <p><b>Qué hace el SP:</b> selecciona desde {@code IT_Plan} todos los registros
     * con {@code Activo=1} y {@code Eliminado=0}, ordenados por {@code PrecioMensual ASC}
     * para mostrarlos de menor a mayor en el frontend.</p>
     *
     * <p><b>Columnas retornadas:</b> {@code PlanId}, {@code Nombre}, {@code Descripcion},
     * {@code PrecioMensual}.</p>
     *
     * <p>Usado en {@code GET /admin/planes} y en el selector de cambio de plan del frontend.</p>
     */
    public List<Map<String, Object>> listarPlanes() {
        return jdbcTemplate.queryForList("EXEC dbo.uspPlanListar");
    }
}
