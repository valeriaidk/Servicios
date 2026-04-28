package pe.extech.utilitarios.modules.correo.domain.ports;

import java.util.List;
import java.util.Map;

/**
 * Puerto de dominio para el envío de correos electrónicos a través de un
 * proveedor externo.
 *
 * <p>
 * Implementaciones actuales:
 * <ul>
 * <li>{@code MicrosoftGraphCorreoProvider} — orden 1, vía OAuth2
 * client_credentials.</li>
 * </ul>
 * </p>
 *
 * <p>
 * Agregar un nuevo proveedor (SMTP propio, SES, SendGrid) sólo requiere crear
 * una clase que implemente esta interfaz y anotarla con {@code @Component}.
 * </p>
 */
public interface CorreoProvider {

    /**
     * Envía un correo a través del proveedor.
     *
     * @param config  configuración del proveedor. Para Graph debe contener
     *                {@code ClientSecretCifrado}.
     * @param mensaje datos del mensaje a enviar.
     * @return referencia de trazabilidad asignada por el proveedor.
     */
    String enviar(Map<String, Object> config, CorreoMensaje mensaje);

    /**
     * Identificador del proveedor (p. ej. {@code "MICROSOFT_GRAPH"}).
     */
    String getProveedor();

    /**
     * Mensaje de dominio con lo mínimo que cualquier proveedor necesita.
     */
    record CorreoMensaje(List<String> destinatarios, String asunto, String cuerpoHtml) {}
}
