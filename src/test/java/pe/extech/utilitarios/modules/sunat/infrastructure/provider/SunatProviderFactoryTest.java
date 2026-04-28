package pe.extech.utilitarios.modules.sunat.infrastructure.provider;

import org.junit.jupiter.api.Test;
import pe.extech.utilitarios.exception.ServicioNoDisponibleException;
import pe.extech.utilitarios.modules.sunat.domain.ports.SunatProvider;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pruebas unitarias del fallback automático entre proveedores SUNAT.
 */
class SunatProviderFactoryTest {

    private static final Map<String, Object> CONFIG_DUMMY = Map.of(
            "ApiServicesFuncionId", 1,
            "EndpointExterno", "http://test/",
            "Token", "TOKEN_EN_CLARO",
            "Autorizacion", "Bearer {TOKEN}");

    @Test
    void primerProveedorRespondeOk_retornaSuResultado() {
        SunatProvider ok = new StubProvider("OK", Map.of("razon_social", "EXTECH SAC"), null);
        SunatProvider backup = new StubProvider("BACKUP", Map.of("razon_social", "NO_DEBERIA_USARSE"), null);

        SunatProviderFactory factory = new SunatProviderFactory(List.of(ok, backup));
        Map<String, Object> resp = factory.consultar(CONFIG_DUMMY, "20100070970");

        assertThat(resp).containsEntry("razon_social", "EXTECH SAC");
    }

    @Test
    void primerProveedorFalla_fallbackAlSiguiente() {
        SunatProvider caido = new StubProvider("CAIDO", null, new RuntimeException("timeout"));
        SunatProvider ok = new StubProvider("OK", Map.of("razon_social", "EXTECH SAC"), null);

        SunatProviderFactory factory = new SunatProviderFactory(List.of(caido, ok));
        Map<String, Object> resp = factory.consultar(CONFIG_DUMMY, "20100070970");

        assertThat(resp).containsEntry("razon_social", "EXTECH SAC");
    }

    @Test
    void todosLosProveedoresFallan_lanzaServicioNoDisponible() {
        SunatProvider a = new StubProvider("A", null, new RuntimeException("timeout A"));
        SunatProvider b = new StubProvider("B", null, new RuntimeException("timeout B"));

        SunatProviderFactory factory = new SunatProviderFactory(List.of(a, b));

        assertThatThrownBy(() -> factory.consultar(CONFIG_DUMMY, "20100070970"))
                .isInstanceOf(ServicioNoDisponibleException.class)
                .hasMessageContaining("Todos los proveedores SUNAT fallaron")
                .hasMessageContaining("timeout B");
    }

    // ------------------------------------------------------------------------
    // Doble de prueba
    // ------------------------------------------------------------------------
    private record StubProvider(String nombre, Map<String, Object> respuesta, RuntimeException error)
            implements SunatProvider {

        @Override
        public Map<String, Object> consultar(Map<String, Object> config, String ruc) {
            if (error != null) {
                throw error;
            }
            return respuesta;
        }

        @Override
        public String getProveedor() {
            return nombre;
        }
    }
}
