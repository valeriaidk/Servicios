package pe.extech.utilitarios.modules.user.domain.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Repositorio de consumo ({@code IT_Consumo}) — acceso exclusivo vía Stored
 * Procedures.
 *
 * <p>
 * Centraliza todas las operaciones sobre {@code IT_Consumo}, que es la única
 * tabla
 * de auditoría y registro del sistema (R3). Ningún otro repositorio escribe en
 * esta tabla.
 * </p>
 *
 * <p>
 * <b>Regla R2:</b> cada request a cualquier servicio (RENIEC, SUNAT, SMS,
 * Correo)
 * registra exactamente 1 entrada en {@code IT_Consumo}, sin excepción. Solo los
 * registros
 * con {@code Exito=1} descuentan del límite mensual del plan.
 * </p>
 *
 * <p>
 * <b>Patrón de acceso:</b> {@code JdbcTemplate.queryForList("EXEC ...")} para
 * SPs
 * de resultado único. Para {@code uspConsumoObtenerHistorialPorUsuario}, que
 * devuelve
 * dos result sets (total + filas paginadas), se usa
 * {@code jdbcTemplate.execute(ConnectionCallback)}
 * con JDBC nativo, porque {@code queryForList} solo lee el primer result set.
 * </p>
 */
@Repository
public class ConsumoRepository {

    private final JdbcTemplate jdbcTemplate;

    public ConsumoRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Registra exactamente 1 consumo en {@code IT_Consumo} (R2).
     *
     * <p>
     * <b>SP ejecutado:</b>
     * {@code uspConsumoRegistrar(@UsuarioId, @ApiServicesFuncionId,
     * @Request, @Response, @Exito, @EsConsulta, @UsuarioRegistro)}
     * </p>
     *
     * <p>
     * <b>Qué hace el SP:</b> inserta una fila en {@code IT_Consumo} con timestamp
     * {@code GETDATE()}, {@code Activo=1} y {@code Eliminado=0}. Retorna el
     * {@code ConsumoId}
     * generado via {@code SCOPE_IDENTITY()}.
     * </p>
     *
     * <p>
     * <b>Parámetros clave:</b>
     * </p>
     * <ul>
     * <li>{@code @Request} / {@code @Response} — payload de entrada y salida,
     * truncados a
     * 4000 caracteres antes de pasarlos al SP para respetar el
     * {@code VARCHAR(4000)}
     * de la columna en BD.</li>
     * <li>{@code @Exito} — {@code 1} si el servicio respondió correctamente,
     * {@code 0} si hubo error del proveedor o límite alcanzado. Solo los
     * {@code Exito=1}
     * descuentan del límite mensual del plan.</li>
     * <li>{@code @EsConsulta} — {@code 1} para RENIEC/SUNAT (consultas de datos),
     * {@code 0} para SMS/Correo (acciones de envío). Discriminador para
     * métricas.</li>
     * <li>{@code @UsuarioRegistro} — nombre visible del usuario
     * ({@code IT_Usuario.Nombre}),
     * truncado a 200 caracteres. {@code NULL} aceptado: el SP tiene
     * {@code DEFAULT NULL}.</li>
     * </ul>
     *
     * <p>
     * Este método siempre se llama, incluso si el servicio externo falló, para
     * garantizar
     * la trazabilidad completa de todos los intentos (R2 + R3).
     * </p>
     *
     * @param nombreUsuario nombre de {@code IT_Usuario.Nombre}; se persiste en
     *                      {@code IT_Consumo.UsuarioRegistro} para legibilidad en
     *                      auditoría
     */
    public Long registrar(int usuarioId, int apiServicesFuncionId,
            String request, String response,
            boolean exito, boolean esConsulta,
            String nombreUsuario) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "EXEC dbo.uspConsumoRegistrar ?, ?, ?, ?, ?, ?, ?",
                usuarioId,
                apiServicesFuncionId,
                truncar(request, 4000),
                truncar(response, 4000),
                exito ? 1 : 0,
                esConsulta ? 1 : 0,
                truncar(nombreUsuario, 200)); // @UsuarioRegistro = nombre visible del usuario
        if (rows.isEmpty())
            return null;
        Object id = rows.get(0).get("ConsumoId");
        return id != null ? ((Number) id).longValue() : null;
    }

    /**
     * Sobrecarga sin {@code nombreUsuario} — para rutas de error donde el nombre
     * no está disponible (ej: fallo antes de resolver el usuario).
     * Persiste {@code NULL} en {@code IT_Consumo.UsuarioRegistro}.
     */
    public Long registrar(int usuarioId, int apiServicesFuncionId,
            String request, String response,
            boolean exito, boolean esConsulta) {
        return registrar(usuarioId, apiServicesFuncionId,
                request, response, exito, esConsulta, null);
    }

    /**
     * Valida si el usuario puede consumir la función según su plan activo.
     *
     * <p>
     * <b>SP ejecutado:</b>
     * {@code uspPlanValidarLimiteUsuario(@UsuarioId, @ApiServicesFuncionId)}
     * </p>
     *
     * <p>
     * <b>Qué hace el SP:</b> obtiene el plan activo del usuario desde
     * {@code IT_PlanUsuario},
     * busca el límite configurado en {@code IT_PlanFuncionLimite} para ese plan y
     * función, y
     * cuenta los consumos exitosos del mes actual en {@code IT_Consumo}. Si no
     * existe registro
     * en {@code IT_PlanFuncionLimite} para esa combinación plan+función (ej: plan
     * ENTERPRISE),
     * el SP considera que no hay límite y retorna {@code PuedeContinuar=1}.
     * </p>
     *
     * <p>
     * <b>Columnas retornadas:</b>
     * </p>
     * <ul>
     * <li>{@code PuedeContinuar} (BIT) — {@code 1} si puede consumir, {@code 0} si
     * alcanzó
     * el límite. El servicio debe cortar aquí si es {@code 0}, registrar el intento
     * con {@code Exito=0} y lanzar {@code LimiteAlcanzadoException}.</li>
     * <li>{@code ConsumoActual} — total de consumos exitosos del mes para esa
     * función.
     * Se incluye en la respuesta al cliente para informar cuánto llevan.</li>
     * <li>{@code LimiteMaximo} — límite configurado en
     * {@code IT_PlanFuncionLimite}.
     * {@code NULL} si el plan no tiene límite para esa función.</li>
     * <li>{@code Plan} — nombre del plan activo (FREE, BASIC, PRO, ENTERPRISE).
     * Se incluye en la respuesta y en el mensaje de error si se supera el
     * límite.</li>
     * <li>{@code MensajeError} — texto descriptivo pre-generado por el SP cuando
     * {@code PuedeContinuar=0}, listo para devolver al cliente.</li>
     * </ul>
     *
     * <p>
     * <b>IMPORTANTE:</b> si el usuario no tiene plan activo, el SP devuelve
     * {@code NombrePlan=''} y {@code PuedeContinuar=1}. El llamador
     * ({@link pe.extech.utilitarios.util.EnvioBaseService})
     * verifica {@code NombrePlan} para bloquear usuarios sin plan (Regla 9).
     * </p>
     */
    public Map<String, Object> validarLimitePlan(int usuarioId, int apiServicesFuncionId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "EXEC dbo.uspPlanValidarLimiteUsuario ?, ?",
                usuarioId, apiServicesFuncionId);
        if (rows.isEmpty()) {
            throw new IllegalStateException(
                    "El SP uspPlanValidarLimiteUsuario no retornó resultado para usuario " + usuarioId + ".");
        }
        return rows.get(0);
    }

    /**
     * Historial paginado de consumos de un usuario.
     *
     * <p>
     * <b>SP ejecutado:</b>
     * {@code uspConsumoObtenerHistorialPorUsuario(@UsuarioId, @PageNumber, @PageSize)}
     * </p>
     *
     * <p>
     * <b>Qué hace el SP:</b> retorna dos result sets en una sola llamada. El
     * primero
     * contiene el total de registros (para la paginación en el frontend). El
     * segundo
     * contiene las filas de la página solicitada, unidas con
     * {@code IT_ApiServicesFuncion}
     * para mostrar el nombre y código de la función consumida.
     * </p>
     *
     * <p>
     * <b>Result set 1:</b> {@code TotalRegistros} — total de consumos del usuario
     * (sin paginar). Necesario para calcular el número de páginas en el frontend.
     * </p>
     *
     * <p>
     * <b>Result set 2 (filas paginadas):</b>
     * </p>
     * <ul>
     * <li>{@code ConsumoId} — identificador del consumo.</li>
     * <li>{@code Funcion} — nombre legible de la función (ej: "Consulta por
     * DNI").</li>
     * <li>{@code CodigoFuncion} — código interno (ej: {@code RENIEC_DNI}).</li>
     * <li>{@code Exito} — BIT leído como {@code boolean} para evitar
     * {@code ClassCastException}.</li>
     * <li>{@code EsConsulta} — BIT que distingue consultas de datos vs. acciones de
     * envío.</li>
     * <li>{@code FechaRegistro} — timestamp del consumo.</li>
     * </ul>
     *
     * <p>
     * <b>Por qué se usa JDBC nativo:</b> {@code queryForList} solo lee el primer
     * result set
     * y descarta los demás. Para leer dos result sets secuencialmente se necesita
     * {@code jdbcTemplate.execute(ConnectionCallback)} con
     * {@code CallableStatement.getMoreResults()}.
     * </p>
     */
    public Map<String, Object> obtenerHistorial(int usuarioId, int pageNumber, int pageSize) {
        return jdbcTemplate.execute((Connection con) -> {
            try (CallableStatement cs = con.prepareCall(
                    "{call dbo.uspConsumoObtenerHistorialPorUsuario(?, ?, ?)}")) {
                cs.setInt(1, usuarioId);
                cs.setInt(2, pageNumber);
                cs.setInt(3, pageSize);

                boolean tieneRs = cs.execute();

                // Primer result set: TotalRegistros
                int total = 0;
                if (tieneRs) {
                    try (ResultSet rs = cs.getResultSet()) {
                        if (rs != null && rs.next()) {
                            total = rs.getInt("TotalRegistros");
                        }
                    }
                }

                // Segundo result set: filas paginadas
                List<Map<String, Object>> registros = new ArrayList<>();
                if (cs.getMoreResults()) {
                    try (ResultSet rs = cs.getResultSet()) {
                        while (rs != null && rs.next()) {
                            Map<String, Object> fila = new LinkedHashMap<>();
                            fila.put("ConsumoId", rs.getLong("ConsumoId"));
                            fila.put("Funcion", rs.getString("Funcion"));
                            fila.put("CodigoFuncion", rs.getString("CodigoFuncion"));
                            // BIT: leer con getBoolean para evitar ClassCastException
                            fila.put("Exito", rs.getBoolean("Exito"));
                            fila.put("EsConsulta", rs.getBoolean("EsConsulta"));
                            fila.put("FechaRegistro", rs.getObject("FechaRegistro"));
                            registros.add(fila);
                        }
                    }
                }

                Map<String, Object> resultado = new LinkedHashMap<>();
                resultado.put("totalRegistros", total);
                resultado.put("registros", registros);
                return resultado;
            }
        });
    }

    /**
     * Total de consumos exitosos del mes actual para el usuario.
     *
     * <p>
     * <b>SP ejecutado:</b>
     * {@code uspConsumoObtenerTotalMensualPorUsuario(@UsuarioId, @ApiServicesFuncionId)}
     * </p>
     *
     * <p>
     * <b>Qué hace el SP:</b> cuenta los registros en {@code IT_Consumo} donde
     * {@code Exito=1} y {@code FechaRegistro} cae en el mes y año actuales.
     * Retorna una sola columna: {@code TotalConsumos}.
     * </p>
     *
     * <p>
     * <b>Uso del parámetro {@code apiServicesFuncionId}:</b>
     * </p>
     * <ul>
     * <li>{@code NULL} — cuenta todos los consumos del mes sin filtrar por función.
     * Usado en {@code GET /usuario/consumo/resumen} para el total global del
     * mes.</li>
     * <li>Con valor — cuenta solo los consumos de esa función específica.
     * Útil para mostrar el consumo por servicio individualmente.</li>
     * </ul>
     */
    public int obtenerTotalMensual(int usuarioId, Integer apiServicesFuncionId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "EXEC dbo.uspConsumoObtenerTotalMensualPorUsuario ?, ?",
                usuarioId, apiServicesFuncionId);
        if (rows.isEmpty())
            return 0;
        Object total = rows.get(0).get("TotalConsumos");
        return total != null ? ((Number) total).intValue() : 0;
    }

    // ─── Utilitario ──────────────────────────────────────────────────────────

    private String truncar(String valor, int maxLen) {
        if (valor == null)
            return null;
        return valor.length() > maxLen ? valor.substring(0, maxLen) : valor;
    }
}
