package pe.extech.utilitarios.modules.sunat.domain.ports;

import java.util.Map;

/**
 * Puerto de dominio: contrato que debe cumplir cualquier proveedor externo
 * que consulte contribuyentes en SUNAT.
 *
 * <p>
 * Implementaciones actuales:
 * <ul>
 * <li>{@code DecolectaSunatProvider} — orden 1, proveedor principal.</li>
 * </ul>
 * </p>
 *
 * <p>
 * Agregar un nuevo proveedor sólo requiere crear una clase que implemente
 * esta interfaz y anotarla con {@code @Component}. La fábrica
 * {@code SunatProviderFactory} la descubrirá automáticamente.
 * </p>
 */
public interface SunatProvider {

    /**
     * Ejecuta la consulta del RUC contra el proveedor externo.
     *
     * @param config configuración del proveedor (endpoint, token, autorización)
     *               resuelta por {@code SunatConfigRepository}.
     * @param ruc    número de RUC de 11 dígitos ya validado.
     * @return mapa crudo con la respuesta del proveedor, listo para ser
     *         mapeado por el UseCase/Mapper.
     */
    Map<String, Object> consultar(Map<String, Object> config, String ruc);

    /**
     * Identificador del proveedor, p. ej. {@code "DECOLECTA"}.
     * Útil para logs, métricas y selección manual si se requiere.
     */
    String getProveedor();
}
