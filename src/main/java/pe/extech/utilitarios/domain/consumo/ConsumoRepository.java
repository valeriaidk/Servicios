package pe.extech.utilitarios.domain.consumo;

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
 * Repositorio de consumo (IT_Consumo) — acceso exclusivo vía Stored Procedures.
 *
 * Regla R2: 1 request = 1 registro en IT_Consumo, siempre, incluso si falla.
 * Regla R3: IT_Consumo es la única tabla de auditoría y registro.
 *
 * Para uspConsumoObtenerHistorialPorUsuario (multi-result-set) se usa
 * jdbcTemplate.execute(ConnectionCallback) con JDBC nativo, porque queryForList
 * solo lee el primer result set.
 */
@Repository
public class ConsumoRepository {

    private final JdbcTemplate jdbcTemplate;

    public ConsumoRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Registra 1 consumo en IT_Consumo (R2: siempre se registra, incluso en error).
     * SP: uspConsumoRegistrar(@UsuarioId, @ApiServicesFuncionId, @Request, @Response,
     *                         @Exito, @EsConsulta, @UsuarioRegistro)
     * Columna retornada: ConsumoId (SCOPE_IDENTITY).
     */
    public Long registrar(int usuarioId, int apiServicesFuncionId,
                          String request, String response,
                          boolean exito, boolean esConsulta) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "EXEC dbo.uspConsumoRegistrar ?, ?, ?, ?, ?, ?, ?",
                usuarioId,
                apiServicesFuncionId,
                truncar(request, 4000),
                truncar(response, 4000),
                exito ? 1 : 0,
                esConsulta ? 1 : 0,
                usuarioId);  // @UsuarioRegistro = mismo usuario que consume
        if (rows.isEmpty()) return null;
        Object id = rows.get(0).get("ConsumoId");
        return id != null ? ((Number) id).longValue() : null;
    }

    /**
     * Valida si el usuario puede consumir la función según su plan.
     * SP: uspPlanValidarLimiteUsuario(@UsuarioId, @ApiServicesFuncionId)
     *
     * Columnas retornadas: PuedeContinuar (BIT), ConsumoActual, LimiteMaximo,
     *                      NombrePlan, MensajeError.
     *
     * IMPORTANTE: si el usuario no tiene plan activo, el SP devuelve NombrePlan = ''
     * y PuedeContinuar = 1 (bug de diseño del SP). El llamador debe verificar
     * NombrePlan para cumplir la Regla 9.
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
     * SP: uspConsumoObtenerHistorialPorUsuario(@UsuarioId, @PageNumber, @PageSize)
     *
     * El SP retorna DOS result sets:
     *   1) SELECT COUNT(1) AS TotalRegistros
     *   2) Filas paginadas con ConsumoId, Funcion, CodigoFuncion, Exito, EsConsulta, FechaRegistro
     *
     * Se usa jdbcTemplate.execute(ConnectionCallback) con JDBC nativo porque
     * queryForList solo lee el primer result set.
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
     * SP: uspConsumoObtenerTotalMensualPorUsuario(@UsuarioId, @ApiServicesFuncionId)
     * @param apiServicesFuncionId null = todas las funciones del mes
     * Columna retornada: TotalConsumos.
     */
    public int obtenerTotalMensual(int usuarioId, Integer apiServicesFuncionId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "EXEC dbo.uspConsumoObtenerTotalMensualPorUsuario ?, ?",
                usuarioId, apiServicesFuncionId);
        if (rows.isEmpty()) return 0;
        Object total = rows.get(0).get("TotalConsumos");
        return total != null ? ((Number) total).intValue() : 0;
    }

    // ─── Utilitario ──────────────────────────────────────────────────────────

    private String truncar(String valor, int maxLen) {
        if (valor == null) return null;
        return valor.length() > maxLen ? valor.substring(0, maxLen) : valor;
    }
}
