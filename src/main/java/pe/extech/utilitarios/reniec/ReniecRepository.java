package pe.extech.utilitarios.reniec;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

/**
 * Repositorio RENIEC — resuelve la configuración del proveedor externo (Decolecta).
 *
 * Usa uspResolverApiExternaPorUsuarioYFuncion(@UsuarioId, @CodigoFuncion)
 * que retorna en una sola llamada: ApiServicesFuncionId, EndpointExterno,
 * Token (cifrado AES-256), Autorizacion, TiempoConsulta.
 *
 * Esto reemplaza las consultas directas a IT_ApiAsignacion/IT_ApiExternaFuncion.
 */
@Repository
public class ReniecRepository {

    private static final String CODIGO_FUNCION = "RENIEC_DNI";

    private final JdbcTemplate jdbcTemplate;

    public ReniecRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Resuelve toda la configuración necesaria para ejecutar la consulta RENIEC.
     * SP: uspResolverApiExternaPorUsuarioYFuncion(@UsuarioId INT, @CodigoFuncion VARCHAR)
     *
     * Columnas clave del resultado:
     *   ApiServicesFuncionId → usado para registrar consumo en IT_Consumo
     *   EndpointExterno      → URL del proveedor Decolecta
     *   Token                → token cifrado AES-256 (descifrar con AesUtil antes de usar)
     *   Autorizacion         → header Authorization (contiene placeholder {TOKEN})
     *   TiempoConsulta       → timeout en segundos
     *
     * @param usuarioId ID del usuario autenticado (validado por el SP)
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
