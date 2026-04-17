package pe.extech.utilitarios.modules.user.domain.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Repositorio de API Keys ({@code IT_Token_Usuario}) — acceso exclusivo vía
 * Stored Procedures.
 *
 * <p>
 * Gestiona el ciclo de vida completo del API Key: inserción inicial al
 * registrarse,
 * consulta del hash activo para validación en cada request, y regeneración
 * manual.
 * </p>
 *
 * <p>
 * <b>Regla R8:</b> el API Key NUNCA se almacena en texto plano. Solo el hash
 * BCrypt
 * se persiste en {@code IT_Token_Usuario.ApiKey}. El valor plano se entrega al
 * usuario
 * únicamente al registrarse o al solicitar regeneración manual, y no es
 * recuperable
 * posteriormente.
 * </p>
 *
 * <p>
 * La tabla {@code IT_Token_Usuario} tiene una restricción {@code UNIQUE} en
 * {@code UsuarioId}: un usuario puede tener a lo sumo un API Key activo a la
 * vez.
 * Esto condiciona la lógica de {@link #desactivarYCrear}.
 * </p>
 */
@Repository
public class TokenRepository {

    private final JdbcTemplate jdbcTemplate;

    public TokenRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Inserta el API Key inicial al registrar un usuario nuevo.
     *
     * <p>
     * <b>SP ejecutado:</b> {@code usp_InsertarTokenUsuario(@UsuarioId, @TokenValue,
     * @FechaInicioVigencia, @FechaFinVigencia, @UsuarioRegistro)}
     * </p>
     *
     * <p>
     * <b>Qué hace el SP:</b> inserta una fila en {@code IT_Token_Usuario} con
     * {@code Activo=1} y {@code Eliminado=0}. El campo {@code @TokenValue} recibe
     * el
     * hash BCrypt — nunca el valor plano (R8). Al final ejecuta un {@code SELECT}
     * para retornar el {@code TokenId} generado.
     * </p>
     *
     * <p>
     * <b>Por qué se usa {@code queryForList} en vez de {@code update}:</b>
     * el SP incluye un {@code SELECT} al final del cuerpo.
     * {@code jdbcTemplate.update()}
     * lanza la excepción <i>"Se ha generado un conjunto de resultados para
     * actualización"</i>
     * cuando el SP retorna filas. {@code queryForList} maneja ambos casos (con o
     * sin
     * result set) sin error.
     * </p>
     *
     * <p>
     * También se llama desde {@link #desactivarYCrear} cuando el usuario no tiene
     * token previo en BD (ej: migración de datos o registro manual en BD).
     * </p>
     *
     * @param apiKeyHash hash BCrypt del API Key — nunca el valor plano (R8)
     */
    public void insertar(int usuarioId, String apiKeyHash,
            LocalDateTime inicio, LocalDateTime fin) {
        jdbcTemplate.queryForList(
                "EXEC dbo.usp_InsertarTokenUsuario ?, ?, ?, ?, ?",
                usuarioId, apiKeyHash, inicio, fin, usuarioId); // @UsuarioRegistro = mismo usuario
    }

    /**
     * Obtiene el hash BCrypt del API Key activo del usuario.
     *
     * <p>
     * <b>SP ejecutado:</b> {@code uspObtenerVigentesPorTokenUsuario(@UsuarioId)}
     * </p>
     *
     * <p>
     * <b>Qué hace el SP:</b> busca en {@code IT_Token_Usuario} el registro con
     * {@code Activo=1}, {@code Eliminado=0} y {@code FechaFinVigencia} mayor a
     * {@code GETDATE()} (o {@code IS NULL} para tokens sin vencimiento).
     * Este manejo de {@code NULL} fue corregido en el SP v2 — sin él, los tokens
     * sin vencimiento nunca eran encontrados.
     * </p>
     *
     * <p>
     * <b>Uso principal:</b> llamado por
     * {@link pe.extech.utilitarios.security.ApiKeyFilter}
     * en cada request a {@code /servicios/**} para obtener el hash y compararlo con
     * el valor plano recibido en el header {@code X-API-Key} mediante BCrypt.
     * </p>
     *
     * @return hash BCrypt del API Key activo, o {@code null} si no hay ninguno
     *         vigente
     */
    public String obtenerActivo(int usuarioId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "EXEC dbo.uspObtenerVigentesPorTokenUsuario ?", usuarioId);
        if (rows.isEmpty())
            return null;
        Object apiKey = rows.get(0).get("ApiKey");
        return apiKey != null ? apiKey.toString() : null;
    }

    /**
     * Genera o regenera el API Key del usuario según el estado actual:
     *
     * - Si YA tiene token en IT_Token_Usuario:
     * → REGENERAR: llama a uspApiKeyDesactivarYCrear (UPDATE del registro
     * existente).
     * Correcto porque la tabla tiene UNIQUE en UsuarioId: no se puede insertar
     * una segunda fila, solo actualizar la existente.
     *
     * - Si NO tiene token previo (ej: UsuarioId = 4 sin registro en
     * IT_Token_Usuario):
     * → CREAR PRIMERA API KEY: llama a usp_InsertarTokenUsuario vía insertar().
     * Sin esto, la ausencia de token terminaría en error 500 (0 filas afectadas).
     *
     * Este método es el único punto del backend que genera o regenera API Keys para
     * usuarios ya registrados. El flujo de registro inicial llama a insertar()
     * directamente.
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
