package pe.extech.utilitarios.sms;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

/**
 * Repositorio SMS — resuelve la configuración del proveedor externo (Infobip).
 *
 * Usa uspResolverApiExternaPorUsuarioYFuncion(@UsuarioId, @CodigoFuncion)
 * que retorna en una sola llamada: ApiServicesFuncionId, EndpointExterno,
 * Token (cifrado AES-256), Autorizacion, TiempoConsulta.
 */
@Repository
public class SmsRepository {

    private static final String CODIGO_FUNCION = "SMS_SEND";

    private final JdbcTemplate jdbcTemplate;

    public SmsRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Resuelve toda la configuración necesaria para enviar SMS vía Infobip.
     * SP: uspResolverApiExternaPorUsuarioYFuncion(@UsuarioId INT, @CodigoFuncion VARCHAR)
     *
     * Columnas clave:
     *   ApiServicesFuncionId, EndpointExterno, Token (AES), Autorizacion, TiempoConsulta.
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
