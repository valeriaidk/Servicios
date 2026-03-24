package pe.extech.utilitarios.reniec.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Respuesta enriquecida de consulta RENIEC.
 *
 * Incluye datos del proveedor (data) más contexto de consumo del plan
 * para trazabilidad en frontend y auditoría.
 *
 * consumoActual refleja el conteo después de registrar este request.
 * limiteMaximo es null cuando el plan no tiene límite (ej: ENTERPRISE).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReniecResponse(
        boolean ok,
        String codigo,
        String mensaje,
        Integer usuarioId,
        String nombreUsuario,
        String plan,
        Integer consumoActual,
        Integer limiteMaximo,
        Integer apiServicesFuncionId,
        // Información del servicio consumido (RENIEC_DNI)
        String servicioNombre,
        String servicioCodigo,
        String servicioDescripcion,
        ReniecData data
) {
    public record ReniecData(
            String dni,
            String nombres,
            String apellidoPaterno,
            String apellidoMaterno,
            String nombreCompleto
    ) {}
}
