package pe.extech.utilitarios.modules.sunat.infrastructure.provider;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import pe.extech.utilitarios.exception.ServicioNoDisponibleException;
import pe.extech.utilitarios.modules.sunat.domain.ports.SunatProvider;

import java.util.List;
import java.util.Map;

/**
 * Selecciona el {@link SunatProvider} a usar y aplica fallback automático entre
 * implementaciones cuando alguna falla.
 *
 * <p>
 * Spring inyecta la lista ordenada por {@code @Order}. Para agregar un nuevo
 * proveedor basta con crear una clase que implemente {@link SunatProvider} y
 * anotarla con {@code @Component} — no es necesario modificar esta fábrica.
 * </p>
 */
@Slf4j
@Component
public class SunatProviderFactory {

    private final List<SunatProvider> providers;

    public SunatProviderFactory(List<SunatProvider> providers) {
        this.providers = providers;
    }

    /**
     * Intenta consultar el RUC con cada proveedor registrado en orden.
     * Si uno falla, registra el error y prueba con el siguiente.
     *
     * @throws ServicioNoDisponibleException si todos los proveedores fallan.
     */
    public Map<String, Object> consultar(Map<String, Object> config, String ruc) {
        Exception ultimoError = null;

        for (SunatProvider provider : providers) {
            try {
                return provider.consultar(config, ruc);
            } catch (Exception e) {
                log.warn("[SUNAT] Proveedor {} falló: {}", provider.getProveedor(), e.getMessage());
                ultimoError = e;
            }
        }

        String msg = "Todos los proveedores SUNAT fallaron";
        if (ultimoError != null) {
            msg += ". Último error: " + ultimoError.getMessage();
        }
        throw new ServicioNoDisponibleException(msg);
    }
}
