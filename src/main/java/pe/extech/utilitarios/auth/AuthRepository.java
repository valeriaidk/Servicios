package pe.extech.utilitarios.auth;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import pe.extech.utilitarios.domain.usuario.UsuarioRepository;

import java.util.List;
import java.util.Map;

/**
 * Repositorio de autenticación — agrupa las consultas necesarias para el flujo de
 * login, registro y gestión del perfil de usuario.
 *
 * <p>Actúa como fachada: algunas operaciones delegan en {@link UsuarioRepository}
 * (para reutilizar lógica ya encapsulada) y otras llaman directamente a SPs propios
 * del dominio de autenticación (planes, configuración).</p>
 *
 * <p>Todos los accesos son vía Stored Procedures; no hay SQL directo contra
 * tablas {@code IT_*} en este repositorio.</p>
 */
@Repository
@RequiredArgsConstructor
public class AuthRepository {

    private final JdbcTemplate jdbcTemplate;
    private final UsuarioRepository usuarioRepository;

    /**
     * Obtiene los datos del usuario por email para el proceso de login.
     *
     * <p>Delega en {@link UsuarioRepository#validarAcceso(String)}, que llama al SP
     * {@code uspUsuarioValidarAcceso(@Email)}. Retorna mapa vacío si el usuario no
     * existe, está inactivo o está eliminado — sin revelar cuál fue la causa.</p>
     *
     * <p><b>Columnas retornadas:</b> {@code UsuarioId}, {@code Nombre}, {@code Apellido},
     * {@code Email}, {@code PasswordHash}, {@code Activo}, {@code Eliminado}.</p>
     */
    public Map<String, Object> obtenerPorEmail(String email) {
        return usuarioRepository.validarAcceso(email);
    }

    /**
     * Obtiene el plan activo del usuario para incluirlo en el JWT y la respuesta de login.
     *
     * <p><b>SP ejecutado:</b> {@code uspPlanObtenerActivoPorUsuario(@UsuarioId)}</p>
     *
     * <p><b>Qué hace el SP:</b> busca en {@code IT_PlanUsuario} el registro con
     * {@code Activo=1} y {@code EstadoSuscripcion='ACTIVO'} para el usuario dado,
     * y lo une con {@code IT_Plan} para retornar el nombre del plan.</p>
     *
     * <p><b>Columnas retornadas:</b> {@code PlanId}, {@code Nombre} (FREE/BASIC/PRO/ENTERPRISE).</p>
     *
     * <p>Retorna mapa vacío si el usuario no tiene plan activo. En ese caso,
     * {@link pe.extech.utilitarios.auth.AuthService} no puede generar el JWT
     * (el {@code planId} es obligatorio en el token).</p>
     */
    public Map<String, Object> obtenerPlanActivo(int usuarioId) {
        List<Map<String, Object>> result = jdbcTemplate.queryForList(
                "EXEC dbo.uspPlanObtenerActivoPorUsuario ?", usuarioId);
        return result.isEmpty() ? Map.of() : result.get(0);
    }

    /**
     * Obtiene el límite mensual mínimo configurado para el plan.
     *
     * <p><b>SP ejecutado:</b> {@code uspPlanObtenerConfiguracionCompleta(@PlanId)}</p>
     *
     * <p><b>Qué hace el SP:</b> retorna una fila por cada función+límite configurada
     * en {@code IT_PlanFuncionLimite} para ese plan, unida con {@code IT_ApiServicesFuncion}.</p>
     *
     * <p><b>Por qué se filtra y reduce en Java:</b> el SP retorna múltiples filas
     * (una por función). Se filtra {@code TipoLimite='MENSUAL'} y se toma el valor
     * mínimo entre todas las funciones para mostrar el límite más restrictivo como
     * referencia en {@code GET /usuario/consumo/resumen}.</p>
     *
     * @return límite mensual mínimo, o {@code null} si el plan no tiene límites
     *         configurados (ej: ENTERPRISE — no hay filas en {@code IT_PlanFuncionLimite})
     */
    public Integer obtenerLimiteMensualPlan(int planId) {
        List<Map<String, Object>> config = jdbcTemplate.queryForList(
                "EXEC dbo.uspPlanObtenerConfiguracionCompleta ?", planId);
        return config.stream()
                .filter(r -> "MENSUAL".equals(r.get("TipoLimite")) && r.get("Limite") != null)
                .map(r -> ((Number) r.get("Limite")).intValue())
                .min(Integer::compareTo)
                .orElse(null);
    }

    /**
     * Obtiene el perfil completo del usuario para {@code GET /usuario/perfil}.
     *
     * <p><b>SP ejecutado:</b> {@code uspUsuarioObtenerPorId(@UsuarioId)}</p>
     *
     * <p><b>Qué hace el SP:</b> selecciona todos los campos de {@code IT_Usuario}
     * filtrando {@code Eliminado=0}. No filtra por {@code Activo} — el perfil es
     * visible aunque la cuenta esté desactivada temporalmente.</p>
     *
     * <p><b>Columnas retornadas:</b> {@code UsuarioId}, {@code Nombre}, {@code Apellido},
     * {@code Email}, {@code Telefono}, {@code RazonSocial}, {@code RUC},
     * {@code Activo}, {@code FechaRegistro}.</p>
     *
     * @return mapa con los datos del usuario, o mapa vacío si no existe o está eliminado
     */
    public Map<String, Object> obtenerDatosUsuario(int usuarioId) {
        List<Map<String, Object>> result = jdbcTemplate.queryForList(
                "EXEC dbo.uspUsuarioObtenerPorId ?", usuarioId);
        return result.isEmpty() ? Map.of() : result.get(0);
    }
}
