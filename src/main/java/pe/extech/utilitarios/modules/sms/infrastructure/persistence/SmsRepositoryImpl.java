package pe.extech.utilitarios.modules.sms.infrastructure.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import pe.extech.utilitarios.modules.sms.domain.repository.SmsConfigRepository;

import java.util.List;
import java.util.Map;

/**
 * Adaptador JDBC del puerto {@link SmsConfigRepository}. Resuelve la
 * configuración del proveedor externo asignado al código de función
 * {@code SMS_SEND} mediante el SP
 * {@code uspResolverApiExternaPorUsuarioYFuncion}.
 */
@Repository
public class SmsRepositoryImpl implements SmsConfigRepository {

    private static final String CODIGO_FUNCION = "SMS_SEND";

    private final JdbcTemplate jdbcTemplate;

    public SmsRepositoryImpl(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Map<String, Object> resolverConfiguracion(int usuarioId) {

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "EXEC dbo.uspResolverApiExternaPorUsuarioYFuncion ?, ?",
                usuarioId, CODIGO_FUNCION);

        if (rows.isEmpty()) {
            throw new IllegalStateException(
                    "No se encontró configuración de proveedor para " + CODIGO_FUNCION
                            + " y usuario " + usuarioId + ". Verifique IT_ApiAsignacion.");
        }

        Map<String, Object> row = rows.get(0);
        if (!row.containsKey("ApiServicesFuncionId") || !row.containsKey("EndpointExterno")) {
            throw new IllegalStateException(
                    "El SP no retornó ApiServicesFuncionId/EndpointExterno para " + CODIGO_FUNCION + ".");
        }
        return row;
    }
}
