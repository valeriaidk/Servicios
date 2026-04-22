package pe.extech.utilitarios.modules.correo.domain.repository;

/**
 * Puerto de dominio para la configuración del servicio de correo.
 *
 * <p>
 * Expone tres operaciones:
 * <ul>
 * <li>{@link #obtenerFuncionId()} — ID interno del servicio CORREO_ENVIO
 * para validar plan y registrar consumo.</li>
 * <li>{@link #obtenerClientSecretCifrado()} — secret cifrado AES-256 del
 * proveedor Microsoft Graph. El UseCase lo descifra al momento del envío.</li>
 * <li>{@link #obtenerAsuntoTemplate(int, String, Integer)} — asunto crudo
 * (sin renderizar) del template solicitado.</li>
 * </ul>
 * </p>
 */
public interface CorreoConfigRepository {

    int obtenerFuncionId();

    String obtenerClientSecretCifrado();

    /**
     * @param funcionId ID del servicio interno (CORREO_ENVIO).
     * @param codigo    código del template en IT_Template.
     * @param version   versión exacta, o {@code null} para la más reciente.
     * @return asunto raw con placeholders {@code {{clave}}}. Puede ser
     *         {@code null} si no hay template o si el template no tiene asunto.
     */
    String obtenerAsuntoTemplate(int funcionId, String codigo, Integer version);
}
