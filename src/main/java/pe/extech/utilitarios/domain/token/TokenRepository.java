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
     * El 5to parámetro @UsuarioRegistro es requerido por el SP.
     *
     * @param apiKeyHash Hash BCrypt del API Key (nunca el valor plano)
     */
    public void insertar(int usuarioId, String apiKeyHash,
                         LocalDateTime inicio, LocalDateTime fin) {
        jdbcTemplate.update(
                "EXEC dbo.usp_InsertarTokenUsuario ?, ?, ?, ?, ?",
                usuarioId, apiKeyHash, inicio, fin, usuarioId); // @UsuarioRegistro = mismo usuario
    }

    /**
     * Obtiene el hash BCrypt del API Key activo de un usuario.
     * Consulta directa: no hay SP disponible para búsqueda por hash a través de todos los usuarios.
     * Retorna null si el usuario no tiene API Key activo y vigente.
     */
    public String obtenerActivo(int usuarioId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT ApiKey FROM dbo.IT_Token_Usuario " +
                "WHERE UsuarioId = ? AND Activo = 1 AND Eliminado = 0 " +
                "AND (FechaFinVigencia IS NULL OR FechaFinVigencia > GETDATE())",
                usuarioId);
        if (rows.isEmpty()) return null;
        Object apiKey = rows.get(0).get("ApiKey");
        return apiKey != null ? apiKey.toString() : null;
    }

    /**
     * Regenera el API Key del usuario (solo cuando lo solicita explícitamente).
     *
     * La tabla IT_Token_Usuario tiene UNIQUE en UsuarioId:
     * existe EXACTAMENTE UN registro por usuario, siempre.
     * El SP uspApiKeyDesactivarYCrear (según CLAUDE.md) hace UPDATE+INSERT, lo cual
     * viola esa constraint y produce:
     *   "Violation of UNIQUE KEY constraint. Cannot insert duplicate key (UsuarioId=N)."
     *
     * Solución: UPDATE directo del registro existente.
     * Se reemplaza ApiKey, fechas y se garantiza Activo=1.
     * No se inserta una fila nueva: la restricción UNIQUE no lo permite.
     *
     * @param nuevoApiKey Hash BCrypt del nuevo API Key (nunca el valor plano)
     * @throws IllegalStateException si el usuario no tiene registro en IT_Token_Usuario
     */
    public void desactivarYCrear(int usuarioId, String nuevoApiKey,
                                 LocalDateTime inicio, LocalDateTime fin) {
        int filasAfectadas = jdbcTemplate.update(
                "UPDATE dbo.IT_Token_Usuario " +
                "SET ApiKey              = ?, " +
                "    FechaInicioVigencia = ?, " +
                "    FechaFinVigencia    = ?, " +
                "    Activo              = 1, " +
                "    Eliminado           = 0, " +
                "    UsuarioModificacion = ?, " +
                "    FechaModificacion   = GETDATE() " +
                "WHERE UsuarioId = ? AND Eliminado = 0",
                nuevoApiKey, inicio, fin, usuarioId, usuarioId);

        if (filasAfectadas == 0) {
            throw new IllegalStateException(
                    "No se encontró registro de API Key para el usuario " + usuarioId +
                    ". Contacta a soporte.");
        }
    }
}
