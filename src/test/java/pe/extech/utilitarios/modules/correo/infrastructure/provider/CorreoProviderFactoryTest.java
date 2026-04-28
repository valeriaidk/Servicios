package pe.extech.utilitarios.modules.correo.infrastructure.provider;

import org.junit.jupiter.api.Test;
import pe.extech.utilitarios.exception.ServicioNoDisponibleException;
import pe.extech.utilitarios.modules.correo.domain.ports.CorreoProvider;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pruebas unitarias del fallback automático entre proveedores de correo.
 */
class CorreoProviderFactoryTest {

    private static final Map<String, Object> CONFIG_DUMMY = Map.of("ClientSecretCifrado", "X");

    private static final CorreoProvider.CorreoMensaje MSG =
            new CorreoProvider.CorreoMensaje(List.of("a@extech.pe"), "Hola", "<p>Hola</p>");

    @Test
    void primerProveedorOk_retornaReferencia() {
        CorreoProvider ok = stub("OK", "REF_OK", null);
        CorreoProvider backup = stub("BACKUP", "NO_DEBERIA", null);

        CorreoProviderFactory factory = new CorreoProviderFactory(List.of(ok, backup));
        String ref = factory.enviar(CONFIG_DUMMY, MSG);

        assertThat(ref).isEqualTo("REF_OK");
    }

    @Test
    void primerProveedorFalla_fallbackAlSiguiente() {
        CorreoProvider caido = stub("CAIDO", null, new RuntimeException("auth"));
        CorreoProvider ok = stub("OK", "REF_B", null);

        CorreoProviderFactory factory = new CorreoProviderFactory(List.of(caido, ok));
        String ref = factory.enviar(CONFIG_DUMMY, MSG);

        assertThat(ref).isEqualTo("REF_B");
    }

    @Test
    void todosFallan_lanzaServicioNoDisponible() {
        CorreoProvider a = stub("A", null, new RuntimeException("e1"));
        CorreoProvider b = stub("B", null, new RuntimeException("e2"));

        CorreoProviderFactory factory = new CorreoProviderFactory(List.of(a, b));

        assertThatThrownBy(() -> factory.enviar(CONFIG_DUMMY, MSG))
                .isInstanceOf(ServicioNoDisponibleException.class)
                .hasMessageContaining("Todos los proveedores")
                .hasMessageContaining("e2");
    }

    private static CorreoProvider stub(String nombre, String referencia, RuntimeException error) {
        return new CorreoProvider() {
            @Override
            public String enviar(Map<String, Object> config, CorreoMensaje mensaje) {
                if (error != null) {
                    throw error;
                }
                return referencia;
            }

            @Override
            public String getProveedor() {
                return nombre;
            }
        };
    }
}
