package pe.extech.utilitarios.modules.auth.infrastructure.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import pe.extech.utilitarios.modules.auth.domain.repository.AuthConfigRepository;
import pe.extech.utilitarios.modules.user.domain.repository.UsuarioRepository;

import java.util.List;
import java.util.Map;

/**
 * Adaptador JDBC del puerto {@link AuthConfigRepository}.
 *
 * <p>
 * Delega en {@link UsuarioRepository} las operaciones ya encapsuladas
 * (validación de acceso, lectura por ID) y llama directamente a los SPs de
 * plan para las consultas específicas del flujo de autenticación.
 * </p>
 */
@Repository
public class AuthRepositoryImpl implements AuthConfigRepository {

    private final JdbcTemplate jdbcTemplate;
    private final UsuarioRepository usuarioRepository;

    public AuthRepositoryImpl(JdbcTemplate jdbcTemplate, UsuarioRepository usuarioRepository) {
        this.jdbcTemplate = jdbcTemplate;
        this.usuarioRepository = usuarioRepository;
    }

    @Override
    public Map<String, Object> obtenerPorEmail(String email) {
        return usuarioRepository.validarAcceso(email);
    }

    @Override
    public Map<String, Object> obtenerPlanActivo(int usuarioId) {
        List<Map<String, Object>> result = jdbcTemplate.queryForList(
                "EXEC dbo.uspPlanObtenerActivoPorUsuario ?", usuarioId);
        return result.isEmpty() ? Map.of() : result.get(0);
    }

    @Override
    public Integer obtenerLimiteMensualPlan(int planId) {
        List<Map<String, Object>> config = jdbcTemplate.queryForList(
                "EXEC dbo.uspPlanObtenerConfiguracionCompleta ?", planId);
        return config.stream()
                .filter(r -> "MENSUAL".equals(r.get("TipoLimite")) && r.get("Limite") != null)
                .map(r -> ((Number) r.get("Limite")).intValue())
                .min(Integer::compareTo)
                .orElse(null);
    }

    @Override
    public Map<String, Object> obtenerDatosUsuario(int usuarioId) {
        List<Map<String, Object>> result = jdbcTemplate.queryForList(
                "EXEC dbo.uspUsuarioObtenerPorId ?", usuarioId);
        return result.isEmpty() ? Map.of() : result.get(0);
    }
}
