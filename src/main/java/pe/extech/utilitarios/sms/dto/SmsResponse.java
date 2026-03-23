package pe.extech.utilitarios.sms.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Respuesta enriquecida de envío de SMS.
 *
 * Incluye datos del envío (proveedor, referencia) más contexto de consumo del plan
 * para trazabilidad en frontend y auditoría.
 *
 * consumoActual refleja el conteo después de registrar este request.
 * limiteMaximo es null cuando el plan no tiene límite (ej: ENTERPRISE).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SmsResponse(
        boolean ok,
        String codigo,
        String mensaje,
        Integer usuarioId,
        String plan,
        Integer consumoActual,
        Integer limiteMaximo,
        Integer apiServicesFuncionId,
        String proveedor,
        String referencia
) {}
