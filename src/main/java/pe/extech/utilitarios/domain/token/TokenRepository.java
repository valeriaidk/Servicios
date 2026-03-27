package pe.extech.utilitarios.domain.token;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Repositorio de tokens / API Keys (IT_Token_Usuario).
 *
 * R8: El API Key NUNCA se almacena en texto plano.
 * Solo se almacena el hash BCrypt en IT_Token_Usuario.ApiKey.
 * El valor plano se entrega al usuario únicamente al registrarse o regenerar manualmente.
 */
@Repository
public class TokenRepository {

    private final JdbcTemplate jdbcTemplate;

    public TokenRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Inserta el API Key inicial al registrar un usuario.
     * SP: usp_InsertarTokenUsuario(@UsuarioId, @TokenValue, @FechaInicioVigencia,
     *                               @FechaFinVigencia, @UsuarioRegistro)
     *
     * Se usa queryForList (no update) porque el SP incluye un SELECT al final
     * (SCOPE_IDENTITY u otro). jdbcTemplate.update() lanza excepción cuando el SP
     * retorna un result set: "Se ha generado un conjunto de resultados para actualización."
     *
     * @param apiKeyHash Hash BCrypt del API Key (nunca el valor plano)
     */
    public void insertar(int usuarioId, String apiKeyHash,
                         LocalDateTime inicio, LocalDateTime fin) {
        jdbcTemplate.queryForList(
                "EXEC dbo.usp_InsertarTokenUsuario ?, ?, ?, ?, ?",
                usuarioId, apiKeyHash, inicio, fin, usuarioId); // @UsuarioRegistro = mismo usuario
    }

    /**
     * Obtiene el hash BCrypt del API Key activo de un usuario.
     * SP: uspObtenerVigentesPorTokenUsuario(@UsuarioId)
     *
     * El SP fue corregido en v2 para manejar FechaFinVigencia IS NULL
     * (tokens sin vencimiento). Retorna null si no hay token activo.
     */
    public String obtenerActivo(int usuarioId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "EXEC dbo.uspObtenerVigentesPorTokenUsuario ?", usuarioId);
        if (rows.isEmpty()) return null;
        Object apiKey = rows.get(0).get("ApiKey");
        return apiKey != null ? apiKey.toString() : null;
    }

    /**
     * Genera o regenera el API Key del usuario según el estado actual:
     *
     *   - Si YA tiene token en IT_Token_Usuario:
     *       → REGENERAR: llama a uspApiKeyDesactivarYCrear (UPDATE del registro existente).
     *         Correcto porque la tabla tiene UNIQUE en UsuarioId: no se puede insertar
     *         una segunda fila, solo actualizar la existente.
     *
     *   - Si NO tiene token previo (ej: UsuarioId = 4 sin registro en IT_Token_Usuario):
     *       → CREAR PRIMERA API KEY: llama a usp_InsertarTokenUsuario vía insertar().
     *         Sin esto, la ausencia de token terminaría en error 500 (0 filas afectadas).
     *
     * Este método es el único punto del backend que genera o regenera API Keys para
     * usuarios ya registrados. El flujo de registro inicial llama a insertar() directamente.
     *
     * @param nuevoApiKey Hash BCrypt del nuevo API Key (nunca el valor plano)
     */
    public void desactivarYCrear(int usuarioId, String nuevoApiKey,
                                 LocalDateTime inicio, LocalDateTime fin) {
        boolean tieneTokenPrevio = (obtenerActivo(usuarioId) != null);

        if (tieneTokenPrevio) {
            // ── Regenerar: actualizar el registro existente vía SP ───────────
            // uspApiKeyDesactivarYCrear en BD es UPDATE-only (no INSERT):
            // la constraint UNIQUE en UsuarioId impide crear una segunda fila.
            jdbcTemplate.queryForList(
                    "EXEC dbo.uspApiKeyDesactivarYCrear ?, ?, ?, ?",
                    usuarioId, nuevoApiKey, inicio, fin);
        } else {
            // ── Primera API Key: insertar registro nuevo vía SP ──────────────
            // El usuario existe pero no tiene token (ej: migración de datos,
            // registro manual en BD, o registro anterior sin token).
            // Se usa el mismo SP que el flujo de registro normal.
            insertar(usuarioId, nuevoApiKey, inicio, fin);
        }
    }
}
