package pe.extech.utilitarios.modules.sunat.domain.repository;

import java.util.Map;

/**
 * Puerto de dominio para la resolución de la configuración del proveedor
 * externo asignado al servicio SUNAT_RUC para un usuario dado.
 *
 * <p>
 * El adaptador actual ({@code SunatRepositoryImpl}) consulta la BD a través del
 * SP {@code uspResolverApiExternaPorUsuarioYFuncion}.
 * </p>
 */
public interface SunatConfigRepository {

    /**
     * @param usuarioId ID del usuario autenticado.
     * @return mapa con al menos las claves:
     *         {@code ApiServicesFuncionId}, {@code EndpointExterno},
     *         {@code Token}, {@code Autorizacion}, {@code TiempoConsulta}.
     */
    Map<String, Object> resolverConfiguracion(int usuarioId);
}
