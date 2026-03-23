package pe.extech.utilitarios.correo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

/**
 * Repositorio Correo.
 *
 * CorreoService usa Microsoft Graph (OAuth2 client_credentials) para el envío.
 * Requiere:
 *   - ApiServicesFuncionId (CORREO_ENVIO) para registrar consumo en IT_Consumo.
 *   - ClientSecret cifrado AES-256 de IT_ApiExternaFuncion (Codigo = MICROSOFT_GRAPH_CORREO).
 *
 * Los identificadores públicos (clientId, tenantId, outlookUser) vienen de
 * application.properties vía @Value en CorreoService — no se almacenan en BD.
 */
@Repository
public class CorreoRepository {

    private static final String CODIGO_FUNCION = "CORREO_ENVIO";

    private final JdbcTemplate jdbcTemplate;

    public CorreoRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Obtiene el ClientSecret cifrado AES-256 almacenado en IT_ApiExternaFuncion
     * para el proveedor MICROSOFT_GRAPH_CORREO.
     *
     * El valor retornado es el token cifrado — CorreoService lo descifra con AesUtil
     * en tiempo de ejecución. Nunca se loguea ni persiste el valor plano.
     *
     * @throws IllegalStateException si el registro no existe o el Token es nulo.
     */
    public String obtenerClientSecretCifrado() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT Token FROM dbo.IT_ApiExternaFuncion " +
                "WHERE Codigo = 'MICROSOFT_GRAPH_CORREO' AND Activo = 1 AND Eliminado = 0");

        if (rows.isEmpty() || rows.get(0).get("Token") == null) {
            throw new IllegalStateException(
                    "ClientSecret de Microsoft Graph no configurado en IT_ApiExternaFuncion " +
                    "(Codigo='MICROSOFT_GRAPH_CORREO'). Guardarlo cifrado via PUT /admin/apis-externas/actualizar.");
        }
        return (String) rows.get(0).get("Token");
    }

    /**
     * Obtiene el ApiServicesFuncionId de CORREO_ENVIO para registrar el consumo.
     * Consulta directa a IT_ApiServicesFuncion por código de negocio.
     * Lanza excepción si la función no está configurada en BD.
     */
    public int obtenerFuncionId() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT ApiServicesFuncionId FROM dbo.IT_ApiServicesFuncion " +
                "WHERE Codigo = ? AND Activo = 1 AND Eliminado = 0",
                CODIGO_FUNCION);

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
}
