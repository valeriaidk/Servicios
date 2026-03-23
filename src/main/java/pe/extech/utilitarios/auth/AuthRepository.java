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
     * Verifica si el email ya está registrado.
     */
    public boolean existeEmail(String email) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM dbo.IT_Usuario WHERE Email = ? AND Eliminado = 0",
                Integer.class, email);
        return count != null && count > 0;
    }

    /**
     * Obtiene el plan activo del usuario (PlanId y Nombre).
     */
    public Map<String, Object> obtenerPlanActivo(int usuarioId) {
        List<Map<String, Object>> result = jdbcTemplate.queryForList(
                "SELECT pu.PlanId, p.Nombre " +
                "FROM dbo.IT_PlanUsuario pu " +
                "INNER JOIN dbo.IT_Plan p ON p.PlanId = pu.PlanId " +
                "WHERE pu.UsuarioId = ? AND pu.Activo = 1 AND pu.Eliminado = 0 " +
                "AND pu.EstadoSuscripcion = 'ACTIVO'",
                usuarioId);
        return result.isEmpty() ? Map.of() : result.get(0);
    }
}
