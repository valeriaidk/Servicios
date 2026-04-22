package pe.extech.utilitarios.modules.sms.domain.ports;

import java.util.Map;

/**
 * Puerto de dominio para el envío de SMS a través de un proveedor externo.
 *
 * <p>
 * Implementaciones actuales:
 * <ul>
 * <li>{@code InfobipSmsProvider} — orden 1, proveedor principal.</li>
 * </ul>
 * </p>
 *
 * <p>
 * Agregar un nuevo proveedor (Twilio, AWS SNS, etc.) solo requiere crear
 * una clase que implemente esta interfaz y anotarla con {@code @Component}.
 * La fábrica {@code SmsProviderFactory} la descubrirá automáticamente.
 * </p>
 */
public interface SmsProvider {

    /**
     * Envía un SMS a través del proveedor.
     *
     * @param config  configuración del proveedor (endpoint, token, autorización)
     *                resuelta por {@code SmsConfigRepository}.
     * @param mensaje datos del mensaje a enviar.
     * @return mapa crudo con la respuesta del proveedor, listo para ser mapeado
     *         por el mapper dedicado a ese proveedor.
     */
    Map<String, Object> enviar(Map<String, Object> config, SmsMensaje mensaje);

    /**
     * Identificador del proveedor (p. ej. {@code "INFOBIP"}). Útil para logs,
     * métricas y selección manual si aplica.
     */
    String getProveedor();

    /**
     * DTO de dominio que transporta lo mínimo que cualquier proveedor necesita
     * saber para enviar un SMS.
     */
    record SmsMensaje(String to, String contenido, String senderId) {}
}
