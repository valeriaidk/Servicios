package pe.extech.utilitarios.modules.sunat;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

/**
 * Repositorio SUNAT — resuelve la configuración del proveedor externo
 * (Decolecta)
 * necesaria para ejecutar la consulta de RUC.
 *
 * <p>
 * Estructura idéntica a
 * {@link pe.extech.utilitarios.modules.reniec.ReniecRepository}:
 * encapsula la llamada al SP {@code uspResolverApiExternaPorUsuarioYFuncion}
 * con el
 * código de función {@code SUNAT_RUC}, sin acceso directo a las tablas
 * {@code IT_ApiAsignacion} ni {@code IT_ApiExternaFuncion}.
 * </p>
 *
 * <p>
 * Patrón uniforme del proyecto: {@code JdbcTemplate.queryForList("EXEC ...")}
 * en lugar de {@code SimpleJdbcCall}, porque {@code SimpleJdbcCall} envuelve
 * los
 * result sets bajo claves auto-generadas ({@code #result-set-1}) que rompen la
 * lectura directa de columnas. {@code queryForList} retorna la lista de filas
 * tal cual.
 * </p>
 */
@Repository
public class SunatRepository {

    private static final String CODIGO_FUNCION = "SUNAT_RUC";

    private final JdbcTemplate jdbcTemplate;

    public SunatRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Resuelve toda la configuración del proveedor Decolecta para la función
     * SUNAT_RUC.
     *
     * <p>
     * <b>SP ejecutado:</b>
     * {@code uspResolverApiExternaPorUsuarioYFuncion(@UsuarioId, @CodigoFuncion)}
     * </p>
     *
     * <p>
     * <b>Qué hace el SP internamente:</b> une {@code IT_ApiServicesFuncion} con
     * {@code IT_ApiAsignacion} y {@code IT_ApiExternaFuncion} para devolver en una
     * sola
     * llamada toda la configuración del proveedor asignado a la función SUNAT_RUC.
     * </p>
     *
     * <p>
     * <b>Columnas retornadas y para qué se usa cada una:</b>
     * </p>
     * <ul>
     * <li>{@code ApiServicesFuncionId} — ID de la función interna SUNAT_RUC.
     * Se pasa a {@code uspPlanValidarLimiteUsuario} y a {@code uspConsumoRegistrar}
     * para identificar qué servicio se está consumiendo.</li>
     * <li>{@code EndpointExterno} — URL completa del endpoint de Decolecta
     * (ej: {@code https://api.decolecta.com/v1/sunat/ruc}).
     * Se usa como {@code baseUrl} del {@code WebClient} para la llamada HTTP
     * GET.</li>
     * <li>{@code Token} — token de autenticación cifrado con AES-256.
     * Se descifra en runtime con {@link pe.extech.utilitarios.util.AesUtil}
     * y se usa para construir el header
     * {@code Authorization: Bearer <token_descifrado>}.
     * Nunca se loguea ni persiste en texto plano (R8).</li>
     * <li>{@code Autorizacion} — template del header de autorización almacenado en
     * BD
     * (ej: {@code "Bearer {TOKEN}"}). El placeholder {@code {TOKEN}} se reemplaza
     * con el token descifrado en tiempo de ejecución.</li>
     * <li>{@code TiempoConsulta} — timeout configurado en segundos para esta
     * función.
     * Si no se usa, el servicio aplica el valor por defecto de
     * {@code application.properties}.</li>
     * </ul>
     *
     * <p>
     * Lanza {@code IllegalStateException} si no existe configuración para la
     * combinación
     * usuario+función en {@code IT_ApiAsignacion}, lo que indica un problema de
     * configuración
     * en BD que debe resolverse antes de poder usar el servicio.
     * </p>
     *
     * @param usuarioId ID del usuario autenticado (extraído del JWT por el
     *                  controller)
     * @return Mapa con las columnas descritas arriba, listo para ser consumido por
     *         {@link SunatService}
     */
    public Map<String, Object> resolverConfiguracion(int usuarioId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "EXEC dbo.uspResolverApiExternaPorUsuarioYFuncion ?, ?",
                usuarioId, CODIGO_FUNCION);

        if (rows.isEmpty()) {
            throw new IllegalStateException(
                    "No se encontró configuración de proveedor para " + CODIGO_FUNCION +
                            " y usuario " + usuarioId + ". Verifique IT_ApiAsignacion.");
        }
        Map<String, Object> row = rows.get(0);
        if (!row.containsKey("ApiServicesFuncionId") || !row.containsKey("EndpointExterno")) {
            throw new IllegalStateException(
                    "El SP no retornó ApiServicesFuncionId/EndpointExterno para " + CODIGO_FUNCION + ".");
        }
        return row;
    }
}
