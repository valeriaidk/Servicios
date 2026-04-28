package pe.extech.utilitarios.modules.correo.infrastructure.provider;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import pe.extech.utilitarios.exception.ServicioNoDisponibleException;
import pe.extech.utilitarios.modules.correo.domain.ports.CorreoProvider;

import java.util.List;
import java.util.Map;

/**
 * Selecciona el {@link CorreoProvider} a usar y aplica fallback automático
 * entre implementaciones cuando alguna falla.
 *
 * <p>
 * Spring inyecta la lista ordenada por {@code @Order}. Para agregar un nuevo
 * proveedor basta con crear una clase que implemente {@link CorreoProvider} y
 * anotarla con {@code @Component}.
 * </p>
 */
@Slf4j
@Component
public class CorreoProviderFactory {

    private final List<CorreoProvider> providers;

    public CorreoProviderFactory(List<CorreoProvider> providers) {
        this.providers = providers;
    }

    /**
     * Intenta enviar el correo con cada proveedor en orden. Si uno falla,
     * registra el error y prueba el siguiente.
     *
     * @throws ServicioNoDisponibleException si todos los proveedores fallan.
     */
    public String enviar(Map<String, Object> config, CorreoProvider.CorreoMensaje mensaje) {
        Exception ultimoError = null;

        for (CorreoProvider provider : providers) {
            try {
                return provider.enviar(config, mensaje);
            } catch (Exception e) {
                log.warn("[CORREO] Proveedor {} falló: {}", provider.getProveedor(), e.getMessage());
                ultimoError = e;
            }
        }

        String msg = "Todos los proveedores de correo fallaron";
        if (ultimoError != null) {
            msg += ". Último error: " + ultimoError.getMessage();
        }
        throw new ServicioNoDisponibleException(msg);
    }
}
