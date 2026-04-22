package pe.extech.utilitarios.modules.sms.domain.repository;

import java.util.Map;

/**
 * Puerto de dominio para la resolución de configuración del proveedor SMS
 * asignado al servicio {@code SMS_SEND} para un usuario dado.
 *
 * <p>
 * El adaptador actual ({@code SmsRepositoryImpl}) consulta la BD a través
 * del SP {@code uspResolverApiExternaPorUsuarioYFuncion}.
 * </p>
 */
public interface SmsConfigRepository {

    /**
     * @param usuarioId ID del usuario autenticado.
     * @return mapa con al menos las claves:
     *         {@code ApiServicesFuncionId}, {@code EndpointExterno},
     *         {@code Token}, {@code Autorizacion}, {@code TiempoConsulta}.
     */
    Map<String, Object> resolverConfiguracion(int usuarioId);
}
