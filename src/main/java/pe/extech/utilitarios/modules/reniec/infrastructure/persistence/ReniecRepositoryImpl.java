package pe.extech.utilitarios.modules.reniec.infrastructure.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import pe.extech.utilitarios.modules.reniec.domain.repository.ReniecConfigRepository;

import java.util.List;
import java.util.Map;

@Repository
public class ReniecRepositoryImpl implements ReniecConfigRepository {

    private static final String CODIGO_FUNCION = "RENIEC_DNI";

    private final JdbcTemplate jdbcTemplate;

    public ReniecRepositoryImpl(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Map<String, Object> resolverConfiguracion(int usuarioId) {

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "EXEC dbo.uspResolverApiExternaPorUsuarioYFuncion ?, ?",
                usuarioId, CODIGO_FUNCION);

        if (rows.isEmpty()) {
            throw new IllegalStateException(
                    "No se encontró configuración de proveedor para " + CODIGO_FUNCION +
                            " y usuario " + usuarioId);
        }

        Map<String, Object> row = rows.get(0);

        if (!row.containsKey("ApiServicesFuncionId") || !row.containsKey("EndpointExterno")) {
            throw new IllegalStateException(
                    "El SP no retornó campos necesarios para " + CODIGO_FUNCION);
        }

        return row;
    }
}