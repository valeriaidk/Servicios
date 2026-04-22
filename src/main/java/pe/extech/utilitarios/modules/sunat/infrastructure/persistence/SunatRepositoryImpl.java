package pe.extech.utilitarios.modules.sunat.infrastructure.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import pe.extech.utilitarios.modules.sunat.domain.repository.SunatConfigRepository;

import java.util.List;
import java.util.Map;

/**
 * Adaptador JDBC del puerto {@link SunatConfigRepository}.
 *
 * <p>
 * Resuelve la configuración del proveedor externo asignado al código de
 * función {@code SUNAT_RUC} ejecutando el SP
 * {@code uspResolverApiExternaPorUsuarioYFuncion(@UsuarioId, @CodigoFuncion)}.
 * </p>
 *
 * <p>
 * Se utiliza {@code JdbcTemplate.queryForList("EXEC ...")} en lugar de
 * {@code SimpleJdbcCall} porque este último envuelve los result sets bajo
 * claves auto-generadas ({@code #result-set-1}) que dificultan la lectura
 * directa de columnas.
 * </p>
 */
@Repository
public class SunatRepositoryImpl implements SunatConfigRepository {

    private static final String CODIGO_FUNCION = "SUNAT_RUC";

    private final JdbcTemplate jdbcTemplate;

    public SunatRepositoryImpl(JdbcTemplate jdbcTemplate) {
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
