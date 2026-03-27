package pe.extech.utilitarios.security;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Utilidad para operaciones con API Keys. Token_Usuario
 *
 * Almacenamiento: solo hash BCrypt en IT_Token_Usuario.ApiKey.
 * El valor plano se entrega al usuario ÚNICAMENTE en el momento de:
 *   - Registro (generación inicial)
 *   - Regeneración manual explícita
 *
 * R8: El texto plano NUNCA se persiste ni se loguea.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ApiKeyUtil {

    private final BCryptPasswordEncoder passwordEncoder;
    private final JdbcTemplate jdbcTemplate;

    /**
     * Genera un nuevo API Key.
     *
     * @return ApiKeyGenerado con el valor plano (para entregar al usuario una sola vez)
     *         y el hash BCrypt (para guardar en BD).
     */
    public ApiKeyGenerado generar() {
        String plano = UUID.randomUUID().toString().replace("-", "");
        String hash = passwordEncoder.encode(plano);
        return new ApiKeyGenerado(plano, hash);
    }

    /**
     * Verifica si el API Key en texto plano coincide con el hash BCrypt almacenado.
     */
    public boolean verificar(String apiKeyPlano, String hashBD) {
        if (apiKeyPlano == null || hashBD == null) return false;
        return passwordEncoder.matches(apiKeyPlano, hashBD);
    }

    /**
     * Busca el usuarioId cuyo API Key activo coincide con el valor en texto plano.
     * SP: uspTokenObtenerTodosActivos — retorna todos los tokens activos y vigentes.
     *
     * La comparación BCrypt es one-way y no puede hacerse en SQL:
     * el SP encapsula solo la query; la verificación ocurre aquí con BCrypt.
     *
     * @return usuarioId si hay coincidencia, null si no.
     */
    public Integer resolverUsuarioId(String apiKeyPlano) {
        if (apiKeyPlano == null || apiKeyPlano.isBlank()) return null;

        List<Map<String, Object>> tokens = jdbcTemplate.queryForList(
                "EXEC dbo.uspTokenObtenerTodosActivos");

        for (Map<String, Object> token : tokens) {
            String hashBD = (String) token.get("ApiKey");
            if (verificar(apiKeyPlano, hashBD)) {
                return ((Number) token.get("UsuarioId")).intValue();
            }
        }
        return null;
    }

    public record ApiKeyGenerado(String plano, String hash) {}
}
