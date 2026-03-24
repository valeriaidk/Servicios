package pe.extech.utilitarios.sms.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Respuesta enriquecida de envío de SMS.
 *
 * Incluye contexto del plan (consumoActual, limiteMaximo), información del servicio
 * (servicioNombre, servicioCodigo, servicioDescripcion) y los datos reales devueltos
 * por Infobip (data: messageId, to, status*).
 *
 * consumoActual refleja el conteo después de registrar este request (consumo anterior + 1).
 * limiteMaximo es null cuando el plan no tiene límite (ej: ENTERPRISE).
 * Los campos null se omiten del JSON por @JsonInclude(NON_NULL).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SmsResponse(
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
        SmsData data
) {
    /**
     * Datos del mensaje tal como los devuelve Infobip.
     *
     * Ejemplo de respuesta real de Infobip:
     * {
     *   "messages": [{
     *     "messageId": "4738764679917950442568",
     *     "status": { "id": 26, "name": "PENDING_ACCEPTED",
     *                 "groupId": 1, "groupName": "PENDING",
     *                 "description": "Message sent to next instance" },
     *     "to": "+51924608148"
     *   }]
     * }
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SmsData(
            String messageId,
            String to,
            Integer statusId,
            String statusName,
            Integer statusGroupId,
            String statusGroupName,
            String statusDescription
    ) {}
}
