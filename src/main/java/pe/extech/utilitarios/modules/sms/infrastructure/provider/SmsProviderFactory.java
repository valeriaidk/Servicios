package pe.extech.utilitarios.modules.sms.infrastructure.provider;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import pe.extech.utilitarios.exception.ServicioNoDisponibleException;
import pe.extech.utilitarios.modules.sms.domain.ports.SmsProvider;

import java.util.List;
import java.util.Map;

/**
 * Selecciona el {@link SmsProvider} a usar y aplica fallback automático entre
 * implementaciones cuando alguna falla.
 *
 * <p>
 * Spring inyecta la lista ordenada por {@code @Order}. Para agregar un nuevo
 * proveedor basta con crear una clase que implemente {@link SmsProvider} y
 * anotarla con {@code @Component} — no se toca esta fábrica.
 * </p>
 */
@Slf4j
@Component
public class SmsProviderFactory {

    private final List<SmsProvider> providers;

    public SmsProviderFactory(List<SmsProvider> providers) {
        this.providers = providers;
    }

    /**
     * Intenta enviar el SMS con cada proveedor registrado en orden. Si uno
     * falla, registra el error y prueba con el siguiente.
     *
     * @throws ServicioNoDisponibleException si todos los proveedores fallan.
     */
    public Map<String, Object> enviar(Map<String, Object> config, SmsProvider.SmsMensaje mensaje) {
        Exception ultimoError = null;

        for (SmsProvider provider : providers) {
            try {
                return provider.enviar(config, mensaje);
            } catch (Exception e) {
                log.warn("[SMS] Proveedor {} falló: {}", provider.getProveedor(), e.getMessage());
                ultimoError = e;
            }
        }

        String msg = "Todos los proveedores SMS fallaron";
        if (ultimoError != null) {
            msg += ". Último error: " + ultimoError.getMessage();
        }
        throw new ServicioNoDisponibleException(msg);
    }
}
