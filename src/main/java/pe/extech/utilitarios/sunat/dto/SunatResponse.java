package pe.extech.utilitarios.sunat.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Respuesta enriquecida de consulta SUNAT.
 *
 * Incluye datos del contribuyente (data) más contexto de consumo del plan
 * para trazabilidad en frontend y auditoría.
 *
 * consumoActual refleja el conteo después de registrar este request.
 * limiteMaximo es null cuando el plan no tiene límite (ej: ENTERPRISE).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SunatResponse(
        boolean ok,
        String codigo,
        String mensaje,
        Integer usuarioId,
        String plan,
        Integer consumoActual,
        Integer limiteMaximo,
        Integer apiServicesFuncionId,
        SunatData data
) {
    public record SunatData(
            String ruc,
            String razonSocial,
            String estado,
            String condicion,
            String direccion
    ) {}
}
