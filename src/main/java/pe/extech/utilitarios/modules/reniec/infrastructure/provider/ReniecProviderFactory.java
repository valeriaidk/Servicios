package pe.extech.utilitarios.modules.reniec.infrastructure.provider;

import org.springframework.stereotype.Component;

import pe.extech.utilitarios.exception.ServicioNoDisponibleException;
import pe.extech.utilitarios.modules.reniec.domain.ports.ReniecProvider;

import java.util.List;
import java.util.Map;

/**
 * Resuelve qué ReniecProvider usar según el campo "Proveedor" de la config.
 * Agregar un nuevo proveedor sólo requiere implementar ReniecProvider y
 * anotarlo con @Component — no hay que tocar esta clase.
 */
@Component
public class ReniecProviderFactory {

    private final List<ReniecProvider> providers;

    public ReniecProviderFactory(List<ReniecProvider> providers) {
        this.providers = providers;
    }

    public Map<String, Object> consultar(Map<String, Object> config, String dni) {
        Exception ultimoError = null;

        for (ReniecProvider provider : providers) {
            try {
                Map<String, Object> resultado = provider.consultar(config, dni);
                return resultado;

            } catch (Exception e) {
                ultimoError = e;
            }
        }

        throw new ServicioNoDisponibleException("Todos los proveedores RENIEC fallaron. " +
                "Último error: " + ultimoError.getMessage());
    }
}