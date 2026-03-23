package pe.extech.utilitarios.correo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

/**
 * Repositorio Correo.
 *
 * CorreoService usa JavaMailSender (SMTP configurado en application.properties),
 * por lo que no necesita token ni endpoint de BD. Solo requiere ApiServicesFuncionId
 * para registrar el consumo en IT_Consumo.
 *
 * uspResolverApiExternaPorUsuarioYFuncion no aplica aquí porque CORREO_ENVIO
 * no tiene IT_ApiAsignacion (usa SMTP, no un proveedor HTTP externo registrado en BD).
 */
@Repository
public class CorreoRepository {

    private static final String CODIGO_FUNCION = "CORREO_ENVIO";

    private final JdbcTemplate jdbcTemplate;

    public CorreoRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
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
