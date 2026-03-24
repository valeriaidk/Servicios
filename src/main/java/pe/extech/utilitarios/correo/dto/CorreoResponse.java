package pe.extech.utilitarios.correo.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Respuesta enriquecida de envío de correo.
 *
 * Alineada con el estilo de SmsResponse, ReniecResponse y SunatResponse:
 *   - servicioNombre / servicioCodigo / servicioDescripcion → identifican el servicio consumido.
 *   - data → detalles del envío: modo, template, destinatarios y referencia del proveedor.
 *
 * consumoActual refleja el conteo después de registrar este request.
 * limiteMaximo es null cuando el plan no tiene límite (ej: ENTERPRISE).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CorreoResponse(
        boolean ok,
        String codigo,
        String mensaje,
        Integer usuarioId,
        String nombreUsuario,
        String plan,
        Integer consumoActual,
        Integer limiteMaximo,
        Integer apiServicesFuncionId,
        String servicioNombre,
        String servicioCodigo,
        String servicioDescripcion,
        CorreoData data
) {
    /**
     * Detalle del envío retornado en el campo "data".
     *
     * mode              → TEMPLATE o INLINE
     * templateCode      → código del template usado (null si mode=INLINE)
     * templateVersion   → versión del template usado (null si mode=INLINE)
     * recipients        → lista de destinatarios a los que se envió el correo
     * totalRecipients   → total de destinatarios
     * providerMessage   → mensaje descriptivo del resultado del proveedor
     * providerReference → referencia única generada por el proveedor (GRAPH_<timestamp>)
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CorreoData(
            String mode,
            String templateCode,
            Integer templateVersion,
            List<String> recipients,
            Integer totalRecipients,
            String providerMessage,
            String providerReference
    ) {}
}
