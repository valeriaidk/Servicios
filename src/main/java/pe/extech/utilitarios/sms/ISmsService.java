package pe.extech.utilitarios.sms;

import pe.extech.utilitarios.sms.dto.SmsRequest;
import pe.extech.utilitarios.sms.dto.SmsResponse;

/**
 * Contrato del servicio de envío de SMS.
 *
 * Implementación actual: SmsService (proveedor Infobip).
 * Un segundo proveedor (ej: Twilio, AWS SNS) solo requiere una nueva
 * implementación de esta interfaz sin tocar el controller.
 */
public interface ISmsService {

    /**
     * Envía un mensaje de texto al destinatario indicado en el request.
     *
     * @param usuarioId  ID del usuario autenticado (extraído del SecurityContext).
     * @param request    DTO con destinatario, mensaje o referencia a template, y variables.
     * @return           Respuesta enriquecida con datos del envío y contexto del plan.
     */
    SmsResponse enviar(int usuarioId, SmsRequest request);
}
