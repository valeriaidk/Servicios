package pe.extech.utilitarios.modules.sms.infrastructure.provider;

import org.junit.jupiter.api.Test;
import pe.extech.utilitarios.exception.ServicioNoDisponibleException;
import pe.extech.utilitarios.modules.sms.domain.ports.SmsProvider;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pruebas unitarias del fallback automático entre proveedores SMS.
 */
class SmsProviderFactoryTest {

    private static final Map<String, Object> CONFIG_DUMMY = Map.of(
            "ApiServicesFuncionId", 1,
            "EndpointExterno", "http://test/",
            "Token", "TOKEN_EN_CLARO",
            "Autorizacion", "App {TOKEN}");

    private static final SmsProvider.SmsMensaje MSG =
            new SmsProvider.SmsMensaje("+51999999999", "hola", "ExtechSMS");

    @Test
    void primerProveedorRespondeOk_retornaSuResultado() {
        SmsProvider ok = stub("OK", Map.of("messages", List.of(Map.of("messageId", "M1"))), null);
        SmsProvider backup = stub("BACKUP", Map.of("messages", List.of(Map.of("messageId", "NO_USAR"))), null);

        SmsProviderFactory factory = new SmsProviderFactory(List.of(ok, backup));
        Map<String, Object> resp = factory.enviar(CONFIG_DUMMY, MSG);

        assertThat(resp.get("messages")).isInstanceOf(List.class);
    }

    @Test
    void primerProveedorFalla_fallbackAlSiguiente() {
        SmsProvider caido = stub("CAIDO", null, new RuntimeException("timeout"));
        SmsProvider ok = stub("OK", Map.of("messages", List.of(Map.of("messageId", "M2"))), null);

        SmsProviderFactory factory = new SmsProviderFactory(List.of(caido, ok));
        Map<String, Object> resp = factory.enviar(CONFIG_DUMMY, MSG);

        assertThat(resp).containsKey("messages");
    }

    @Test
    void todosLosProveedoresFallan_lanzaServicioNoDisponible() {
        SmsProvider a = stub("A", null, new RuntimeException("auth"));
        SmsProvider b = stub("B", null, new RuntimeException("timeout"));

        SmsProviderFactory factory = new SmsProviderFactory(List.of(a, b));

        assertThatThrownBy(() -> factory.enviar(CONFIG_DUMMY, MSG))
                .isInstanceOf(ServicioNoDisponibleException.class)
                .hasMessageContaining("Todos los proveedores SMS fallaron")
                .hasMessageContaining("timeout");
    }

    // ------------------------------------------------------------------------
    // Doble de prueba
    // ------------------------------------------------------------------------
    private static SmsProvider stub(String nombre, Map<String, Object> respuesta, RuntimeException error) {
        return new SmsProvider() {
            @Override
            public Map<String, Object> enviar(Map<String, Object> config, SmsMensaje mensaje) {
                if (error != null) {
                    throw error;
                }
                return respuesta;
            }

            @Override
            public String getProveedor() {
                return nombre;
            }
        };
    }
}
