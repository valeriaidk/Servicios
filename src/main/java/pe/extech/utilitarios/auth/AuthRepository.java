package pe.extech.utilitarios.auth;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import pe.extech.utilitarios.domain.usuario.UsuarioRepository;

import java.util.List;
import java.util.Map;

/**
 * Repositorio de autenticación.
 * Delega en SPs existentes vía UsuarioRepository.
 */
@Repository
@RequiredArgsConstructor
public class AuthRepository {

    private final JdbcTemplate jdbcTemplate;
    private final UsuarioRepository usuarioRepository;

    /**
     * Obtiene datos del usuario para login (PasswordHash, Activo, PlanId, etc.).
     */
    public Map<String, Object> obtenerPorEmail(String email) {
        return usuarioRepository.validarAcceso(email);
    }

    /**
     * Obtiene el plan activo del usuario (PlanId y Nombre).
     * SP: uspPlanObtenerActivoPorUsuario(@UsuarioId)
     * Retorna Map vacío si el usuario no tiene plan activo.
     */
    public Map<String, Object> obtenerPlanActivo(int usuarioId) {
        List<Map<String, Object>> result = jdbcTemplate.queryForList(
                "EXEC dbo.uspPlanObtenerActivoPorUsuario ?", usuarioId);
        return result.isEmpty() ? Map.of() : result.get(0);
    }

    /**
     * Obtiene el límite mensual mínimo configurado para el plan.
     * SP: uspPlanObtenerConfiguracionCompleta(@PlanId) — retorna una fila por función/límite.
     * Se filtra TipoLimite = 'MENSUAL' y se toma el valor mínimo en Java.
     * Retorna null si el plan no tiene límites (ej: ENTERPRISE).
     * Usado para mostrar limiteMaximo en GET /usuario/consumo/resumen.
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
     * Obtiene los datos personales del usuario.
     * SP: uspUsuarioObtenerPorId(@UsuarioId)
     * Retorna: UsuarioId, Nombre, Apellido, Email, Telefono, RazonSocial, RUC, Activo, FechaRegistro.
     * Retorna Map vacío si el usuario no existe o está eliminado.
     */
    public Map<String, Object> obtenerDatosUsuario(int usuarioId) {
        List<Map<String, Object>> result = jdbcTemplate.queryForList(
                "EXEC dbo.uspUsuarioObtenerPorId ?", usuarioId);
        return result.isEmpty() ? Map.of() : result.get(0);
    }
}
