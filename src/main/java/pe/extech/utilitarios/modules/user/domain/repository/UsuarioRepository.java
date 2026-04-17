package pe.extech.utilitarios.modules.user.domain.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

/**
 * Repositorio de usuarios ({@code IT_Usuario}) — acceso exclusivo vía Stored
 * Procedures.
 *
 * <p>
 * Centraliza todas las operaciones sobre el usuario: creación, validación de
 * acceso,
 * activación/desactivación y lectura de datos. Usado por
 * {@link pe.extech.utilitarios.modules.auth.AuthService},
 * {@link pe.extech.utilitarios.modules.auth.AuthRepository} y los servicios de
 * consumo para
 * resolver el nombre del usuario en auditoría.
 * </p>
 *
 * <p>
 * <b>Patrón uniforme:</b> {@code JdbcTemplate.queryForList("EXEC ...")} en
 * lugar
 * de {@code SimpleJdbcCall}, porque {@code SimpleJdbcCall} envuelve los result
 * sets
 * bajo claves auto-generadas ({@code #result-set-1}) que rompen la lectura
 * directa
 * de columnas. {@code queryForList} retorna la lista de filas tal cual.
 * </p>
 *
 * <p>
 * Los métodos lanzan {@code IllegalStateException} si el SP no retorna el
 * resultado
 * esperado, lo que indica un problema de configuración en BD o un bug en el SP.
 * </p>
 */
@Repository
public class UsuarioRepository {

    private final JdbcTemplate jdbcTemplate;

    public UsuarioRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Crea un usuario nuevo y le asigna el plan FREE automáticamente.
     *
     * <p>
     * <b>SP ejecutado:</b> {@code uspIT_UsuarioGuardarActulizar} (typo intencional
     * en el nombre — no modificar para mantener compatibilidad con BD).
     * </p>
     *
     * <p>
     * <b>Qué hace el SP:</b> inserta el usuario en {@code IT_Usuario} con
     * {@code Activo=1} y {@code Eliminado=0}, y crea el registro correspondiente
     * en {@code IT_PlanUsuario} con el plan FREE
     * ({@code EstadoSuscripcion='ACTIVO'}).
     * Si el email ya existe retorna un mensaje de error en la columna
     * {@code Mensaje}
     * sin lanzar excepción SQL, por lo que el llamador debe verificar
     * {@code Accion}.
     * </p>
     *
     * <p>
     * <b>Por qué se usan parámetros nombrados</b> ({@code @Nombre = ?, ...}):
     * el SP tiene varios parámetros opcionales con valores DEFAULT
     * ({@code @UsuarioId=NULL},
     * {@code @PlanId=NULL}, {@code @Activo=1}, etc.). Los parámetros nombrados
     * permiten
     * omitirlos sin necesidad de pasarlos explícitamente en orden posicional.
     * </p>
     *
     * <p>
     * <b>Columnas retornadas:</b>
     * </p>
     * <ul>
     * <li>{@code UsuarioId} — ID del usuario creado. Se usa para generar el API Key
     * (pasado a {@code usp_InsertarTokenUsuario}) y construir el JWT.</li>
     * <li>{@code PlanId} — ID del plan asignado (FREE por defecto). Se incluye en
     * el JWT.</li>
     * <li>{@code Accion} — {@code 'INSERT'} si fue creación, otro valor si hubo
     * conflicto.</li>
     * <li>{@code Mensaje} — descripción del resultado o mensaje de error del
     * SP.</li>
     * </ul>
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
     * Obtiene los datos del usuario por email para el proceso de login.
     *
     * <p>
     * <b>SP ejecutado:</b> {@code uspUsuarioValidarAcceso(@Email)}
     * </p>
     *
     * <p>
     * <b>Qué hace el SP:</b> busca en {@code IT_Usuario} por email exacto,
     * filtrando {@code Activo=1} y {@code Eliminado=0}. Si el usuario existe y está
     * activo, retorna su fila completa. Si no existe o está inactivo/eliminado,
     * retorna vacío (sin lanzar excepción SQL).
     * </p>
     *
     * <p>
     * <b>Columnas retornadas:</b> {@code UsuarioId}, {@code Nombre},
     * {@code Apellido},
     * {@code Email}, {@code PasswordHash}, {@code Activo}, {@code Eliminado}.
     * </p>
     *
     * <p>
     * <b>Por qué retorna mapa vacío en vez de lanzar excepción:</b>
     * {@link pe.extech.utilitarios.modules.auth.AuthService} necesita distinguir
     * entre
     * "usuario no existe" y "contraseña incorrecta" sin revelar cuál fue la causa
     * al cliente (ambos retornan 401 {@code CREDENCIALES_INVALIDAS}). El mapa vacío
     * permite esa distinción internamente sin exponer información sensible.
     * </p>
     */
    public Map<String, Object> validarAcceso(String email) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "EXEC dbo.uspUsuarioValidarAcceso ?", email);
        // Retorna Map vacío intencionalmente: AuthService distingue no-encontrado vs.
        // contraseña incorrecta
        return rows.isEmpty() ? Map.of() : rows.get(0);
    }

    /**
     * Activa un usuario (establece {@code Activo=1} en {@code IT_Usuario}).
     *
     * <p>
     * <b>SP ejecutado:</b>
     * {@code uspUsuarioActivarDesactivar(@UsuarioId, @Activo=1, @UsuarioAccion)}
     * </p>
     *
     * <p>
     * <b>Qué hace el SP:</b> hace {@code UPDATE IT_Usuario SET Activo=1} para el
     * usuario
     * indicado, registra quién realizó la acción ({@code @UsuarioAccion}) y la
     * fecha.
     * Solo afecta usuarios con {@code Eliminado=0}.
     * </p>
     *
     * <p>
     * <b>Columna retornada:</b> {@code FilasAfectadas} — cantidad de filas
     * modificadas.
     * {@code 0} indica que el usuario no existe o ya estaba activo.
     * </p>
     *
     * @param usuarioAccion ID del admin que ejecuta la acción, para auditoría en BD
     */
    public int activar(int usuarioId, int usuarioAccion) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "EXEC dbo.uspUsuarioActivarDesactivar ?, ?, ?",
                usuarioId, 1, usuarioAccion);
        if (rows.isEmpty())
            return 0;
        Object filas = rows.get(0).get("FilasAfectadas");
        return filas != null ? ((Number) filas).intValue() : 0;
    }

    /**
     * Desactiva un usuario (establece {@code Activo=0} en {@code IT_Usuario}).
     *
     * <p>
     * <b>SP ejecutado:</b>
     * {@code uspUsuarioActivarDesactivar(@UsuarioId, @Activo=0, @UsuarioAccion)}
     * </p>
     *
     * <p>
     * <b>Qué hace el SP:</b> hace {@code UPDATE IT_Usuario SET Activo=0} para el
     * usuario
     * indicado, registra quién realizó la acción y la fecha. Un usuario desactivado
     * sigue
     * existiendo en BD (borrado lógico: {@code Eliminado=0}), pero no puede hacer
     * login
     * porque {@code uspUsuarioValidarAcceso} filtra {@code Activo=1}.
     * </p>
     *
     * <p>
     * <b>Columna retornada:</b> {@code FilasAfectadas} — {@code 0} si el usuario no
     * existe o ya estaba inactivo.
     * </p>
     *
     * @param usuarioAccion ID del admin que ejecuta la acción, para auditoría en BD
     */
    public int desactivar(int usuarioId, int usuarioAccion) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "EXEC dbo.uspUsuarioActivarDesactivar ?, ?, ?",
                usuarioId, 0, usuarioAccion);
        if (rows.isEmpty())
            return 0;
        Object filas = rows.get(0).get("FilasAfectadas");
        return filas != null ? ((Number) filas).intValue() : 0;
    }

    /**
     * Retorna el nombre del usuario por su ID.
     *
     * <p>
     * <b>SP ejecutado:</b> {@code uspUsuarioObtenerPorId(@UsuarioId)}
     * </p>
     *
     * <p>
     * <b>Qué hace el SP:</b> selecciona todos los campos de {@code IT_Usuario}
     * para el {@code UsuarioId} dado, filtrando {@code Eliminado=0}. Este método
     * solo lee la columna {@code Nombre}; los demás campos los usa
     * {@link AuthRepository#obtenerDatosUsuario}.
     * </p>
     *
     * <p>
     * <b>Para qué se usa:</b> el nombre se persiste en
     * {@code IT_Consumo.UsuarioRegistro}
     * (auditoría legible) y se incluye en las respuestas de los servicios de
     * consumo.
     * Se llama al inicio de cada servicio para tenerlo disponible en todos los
     * paths,
     * incluidos los de error.
     * </p>
     *
     * @return {@code Nombre} del usuario, o {@code null} si no existe o está
     *         eliminado
     */
    public String obtenerNombrePorId(int usuarioId) {
        // El llamador solo necesita Nombre; el SP retorna todos los campos del usuario.
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "EXEC dbo.uspUsuarioObtenerPorId ?", usuarioId);
        if (rows.isEmpty())
            return null;
        return (String) rows.get(0).get("Nombre");
    }
}
