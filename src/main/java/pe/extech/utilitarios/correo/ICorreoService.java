package pe.extech.utilitarios.correo;

import pe.extech.utilitarios.correo.dto.CorreoRequest;
import pe.extech.utilitarios.correo.dto.CorreoResponse;

/**
 * Contrato del servicio de envío de correo electrónico.
 *
 * Implementación actual: CorreoService (Microsoft Graph OAuth2).
 * Un segundo proveedor (ej: SendGrid directo, AWS SES) solo requiere
 * una nueva implementación de esta interfaz sin tocar el controller.
 */
public interface ICorreoService {

    /**
     * Envía un correo electrónico a los destinatarios indicados en el request.
     *
     * @param usuarioId  ID del usuario autenticado (extraído del SecurityContext).
     * @param request    DTO con destinatarios, asunto o referencia a template, y variables.
     * @return           Respuesta enriquecida con datos del envío y contexto del plan.
     */
    CorreoResponse enviar(int usuarioId, CorreoRequest request);
}
