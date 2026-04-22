package pe.extech.utilitarios.modules.sunat.application.interfaces;

import pe.extech.utilitarios.modules.sunat.dto.SunatResponse;

/**
 * Contrato de los casos de uso del módulo SUNAT expuestos al controller.
 *
 * <p>
 * Implementado por {@code ConsultarRucUseCase} — delega la llamada al
 * proveedor a la fábrica {@code SunatProviderFactory}, validando previamente
 * el formato del RUC y el plan del usuario.
 * </p>
 */
public interface ISunatUseCases {

    /**
     * Consulta los datos de un contribuyente en SUNAT por su número de RUC.
     *
     * @param usuarioId ID del usuario autenticado (extraído del JWT).
     * @param numeroRuc RUC de 11 dígitos que comienza con 10 ó 20.
     * @return {@link SunatResponse} con los datos del contribuyente y el
     *         contexto del plan consumido.
     */
    SunatResponse consultarRuc(int usuarioId, String numeroRuc);
}
