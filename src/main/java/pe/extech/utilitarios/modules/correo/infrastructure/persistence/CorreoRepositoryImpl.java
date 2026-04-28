package pe.extech.utilitarios.modules.correo.infrastructure.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import pe.extech.utilitarios.modules.correo.domain.repository.CorreoConfigRepository;

import java.util.List;
import java.util.Map;

/**
 * Adaptador JDBC del puerto {@link CorreoConfigRepository}.
 *
 * <p>
 * Encapsula tres SPs: obtención de {@code ApiServicesFuncionId} para el código
 * CORREO_ENVIO, lectura del {@code ClientSecret} cifrado AES-256 del proveedor
 * {@code MICROSOFT_GRAPH_CORREO} y resolución del asunto del template.
 * </p>
 */
@Repository
public class CorreoRepositoryImpl implements CorreoConfigRepository {

    private static final String CODIGO_FUNCION = "CORREO_ENVIO";
    private static final String CODIGO_PROVEEDOR = "MICROSOFT_GRAPH_CORREO";

    private final JdbcTemplate jdbcTemplate;

    public CorreoRepositoryImpl(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public int obtenerFuncionId() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "EXEC dbo.uspApiServicesFuncionObtenerPorCodigo ?", CODIGO_FUNCION);
        if (rows.isEmpty()) {
            throw new IllegalStateException(
                    "La función " + CODIGO_FUNCION + " no está configurada en IT_ApiServicesFuncion.");
        }
        Object id = rows.get(0).get("ApiServicesFuncionId");
        if (id == null) {
            throw new IllegalStateException("ApiServicesFuncionId es nulo para " + CODIGO_FUNCION + ".");
        }
        return ((Number) id).intValue();
    }

    @Override
    public String obtenerClientSecretCifrado() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "EXEC dbo.uspApiExternaObtenerPorCodigo ?, ?",
                CODIGO_PROVEEDOR, 1);
        if (rows.isEmpty() || rows.get(0).get("Token") == null) {
            throw new IllegalStateException(
                    "ClientSecret de Microsoft Graph no configurado en IT_ApiExternaFuncion "
                            + "(Codigo='" + CODIGO_PROVEEDOR + "'). Guardarlo cifrado "
                            + "via PUT /admin/apis-externas/actualizar.");
        }
        return (String) rows.get(0).get("Token");
    }

    @Override
    public String obtenerAsuntoTemplate(int funcionId, String codigo, Integer version) {
        if (codigo == null || codigo.isBlank()) {
            return null;
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "EXEC dbo.uspTemplateObtenerAsunto ?, ?, ?",
                funcionId, codigo, version);
        if (rows.isEmpty()) {
            return null;
        }
        return (String) rows.get(0).get("AsuntoTemplate");
    }
}
