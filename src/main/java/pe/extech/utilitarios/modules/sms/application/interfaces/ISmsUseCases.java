package pe.extech.utilitarios.modules.sms.application.interfaces;

import pe.extech.utilitarios.modules.sms.dto.SmsRequest;
import pe.extech.utilitarios.modules.sms.dto.SmsResponse;

/**
 * Contrato de los casos de uso del módulo SMS expuestos al controller.
 */
public interface ISmsUseCases {

    /**
     * Envía un SMS en modo INLINE (texto libre) o TEMPLATE (plantilla en
     * {@code IT_Template} con sustitución de variables).
     *
     * @param usuarioId ID del usuario autenticado (extraído del JWT).
     * @param request   contrato de entrada validado.
     * @return {@link SmsResponse} con el resultado del proveedor y el contexto
     *         del plan.
     */
    SmsResponse enviar(int usuarioId, SmsRequest request);
}
