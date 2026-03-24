package pe.extech.utilitarios.domain.usuario;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

/**
 * Repositorio de usuarios — acceso exclusivo vía Stored Procedures.
 *
 * Patrón uniforme: queryForList("EXEC ...") en vez de SimpleJdbcCall,
 * porque SimpleJdbcCall envuelve los result sets bajo claves auto-generadas
 * (#result-set-1). queryForList devuelve directamente la lista de filas.
 *
 * Los métodos lanzan excepción si la operación no produce resultado esperado.
 */
@Repository
public class UsuarioRepository {

    private final JdbcTemplate jdbcTemplate;

    public UsuarioRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Crea un usuario nuevo y asigna el plan FREE automáticamente.
     * SP: uspIT_UsuarioGuardarActulizar (typo intencional — no modificar el nombre).
     *
     * Se usan parámetros nombrados para que los campos opcionales con defaults
     * del SP (@UsuarioId=NULL, @PlanId=NULL, @Activo=1, etc.) sean respetados
     * sin necesidad de pasarlos explícitamente.
     *
     * Columnas retornadas: UsuarioId, PlanId, Accion, Mensaje.
     */
    public Map<String, Object> guardarOActualizar(String nombre, String apellido, String email,
                                                   String passwordHash, String telefono,
                                                   String razonSocial, String ruc) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "EXEC dbo.uspIT_UsuarioGuardarActulizar " +
                "@Nombre = ?, @Apellido = ?, @Email = ?, @PasswordHash = ?, " +
                "@Telefono = ?, @RazonSocial = ?, @RUC = ?",
                nombre, apellido, email, passwordHash, telefono, razonSocial, ruc);

        if (rows.isEmpty()) {
            throw new IllegalStateException("El SP uspIT_UsuarioGuardarActulizar no retornó resultado.");
        }
        Map<String, Object> row = rows.get(0);
        if (!row.containsKey("UsuarioId") || !row.containsKey("PlanId")) {
            throw new IllegalStateException("El SP no retornó UsuarioId/PlanId esperados.");
        }
        return row;
    }

    /**
     * Valida credenciales por email para el login.
     * SP: uspUsuarioValidarAcceso(@Email)
     *
     * Columnas retornadas: UsuarioId, Nombre, Apellido, Email, PasswordHash, Activo, Eliminado.
     * Retorna mapa vacío si el usuario no existe o está inactivo/eliminado
     * (el SP filtra AND Activo=1 AND Eliminado=0).
     */
    public Map<String, Object> validarAcceso(String email) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "EXEC dbo.uspUsuarioValidarAcceso ?", email);
        // Retorna Map vacío intencionalmente: AuthService distingue no-encontrado vs. contraseña incorrecta
        return rows.isEmpty() ? Map.of() : rows.get(0);
    }

    /**
     * Activa un usuario.
     * SP: uspUsuarioActivarDesactivar(@UsuarioId, @Activo=1, @UsuarioAccion)
     * Columna retornada: FilasAfectadas.
     */
    public int activar(int usuarioId, int usuarioAccion) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "EXEC dbo.uspUsuarioActivarDesactivar ?, ?, ?",
                usuarioId, 1, usuarioAccion);
        if (rows.isEmpty()) return 0;
        Object filas = rows.get(0).get("FilasAfectadas");
        return filas != null ? ((Number) filas).intValue() : 0;
    }

    /**
     * Desactiva un usuario.
     * SP: uspUsuarioActivarDesactivar(@UsuarioId, @Activo=0, @UsuarioAccion)
     * Columna retornada: FilasAfectadas.
     */
    public int desactivar(int usuarioId, int usuarioAccion) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "EXEC dbo.uspUsuarioActivarDesactivar ?, ?, ?",
                usuarioId, 0, usuarioAccion);
        if (rows.isEmpty()) return 0;
        Object filas = rows.get(0).get("FilasAfectadas");
        return filas != null ? ((Number) filas).intValue() : 0;
    }

    /**
     * Retorna el nombre del usuario por su ID.
     * Se usa para enriquecer las respuestas de los servicios con el nombre visible
     * del usuario, y para persistirlo en IT_Consumo.UsuarioRegistro.
     *
     * Retorna null si el usuario no existe, está inactivo o eliminado.
     */
    public String obtenerNombrePorId(int usuarioId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT Nombre FROM dbo.IT_Usuario WHERE UsuarioId = ? AND Activo = 1 AND Eliminado = 0",
                usuarioId);
        if (rows.isEmpty()) return null;
        return (String) rows.get(0).get("Nombre");
    }
}
