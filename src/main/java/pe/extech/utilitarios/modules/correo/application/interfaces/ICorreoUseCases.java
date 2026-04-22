package pe.extech.utilitarios.modules.correo.application.interfaces;

import pe.extech.utilitarios.modules.correo.dto.CorreoRequest;
import pe.extech.utilitarios.modules.correo.dto.CorreoResponse;

/**
 * Contrato de los casos de uso del módulo Correo expuestos al controller.
 */
public interface ICorreoUseCases {

    /**
     * Envía un correo en modo INLINE (subject + body_html directos) o TEMPLATE
     * (plantilla HTML del classpath + asunto de {@code IT_Template}).
     */
    CorreoResponse enviar(int usuarioId, CorreoRequest request);
}
