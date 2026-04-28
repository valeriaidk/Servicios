package pe.extech.utilitarios.modules.sms.application.usecases;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import pe.extech.utilitarios.exception.LimiteAlcanzadoException;
import pe.extech.utilitarios.exception.ServicioNoDisponibleException;
import pe.extech.utilitarios.modules.sms.domain.ports.SmsProvider;
import pe.extech.utilitarios.modules.sms.domain.repository.SmsConfigRepository;
import pe.extech.utilitarios.modules.sms.dto.SmsRequest;
import pe.extech.utilitarios.modules.sms.dto.SmsResponse;
import pe.extech.utilitarios.modules.sms.infrastructure.mapper.InfobipSmsMapper;
import pe.extech.utilitarios.modules.sms.infrastructure.provider.SmsProviderFactory;
import pe.extech.utilitarios.modules.user.domain.repository.ConsumoRepository;
import pe.extech.utilitarios.modules.user.domain.repository.UsuarioRepository;
import pe.extech.utilitarios.util.PlantillaUtil;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Pruebas unitarias del caso de uso {@link EnviarSmsUseCase}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EnviarSmsUseCaseTest {

    private static final int USUARIO_ID = 1;
    private static final int FUNCION_ID = 20;
    private static final String TO_VALIDO = "+51999888777";

    @Mock private SmsConfigRepository smsRepository;
    @Mock private ConsumoRepository consumoRepository;
    @Mock private UsuarioRepository usuarioRepository;
    @Mock private SmsProvider smsProvider;
    @Mock private PlantillaUtil plantillaUtil;

    private EnviarSmsUseCase useCase;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        InfobipSmsMapper mapper = new InfobipSmsMapper();
        SmsProviderFactory factory = new SmsProviderFactory(List.of(smsProvider));
        useCase = new EnviarSmsUseCase(
                smsRepository, consumoRepository, usuarioRepository,
                factory, mapper, plantillaUtil, objectMapper, "ExtechSMS");
    }

    @Test
    void envioInlineExitoso_retornaResponseYRegistraConsumoExitoso() {
        stubComun(true);
        when(smsProvider.enviar(anyMap(), any())).thenReturn(Map.of(
                "messages", List.of(Map.of(
                        "messageId", "MSG-123",
                        "to", TO_VALIDO,
                        "status", Map.of("id", 26, "name", "PENDING_ACCEPTED",
                                "groupId", 1, "groupName", "PENDING",
                                "description", "Message sent to next instance")))));

        SmsRequest req = new SmsRequest(null, "INLINE", null, TO_VALIDO,
                null, "Hola desde Extech", null);

        SmsResponse resp = useCase.enviar(USUARIO_ID, req);

        assertThat(resp.ok()).isTrue();
        assertThat(resp.data().messageId()).isEqualTo("MSG-123");
        assertThat(resp.data().statusName()).isEqualTo("PENDING_ACCEPTED");
        verify(consumoRepository).registrar(eq(USUARIO_ID), eq(FUNCION_ID), anyString(),
                anyString(), eq(true), eq(false), anyString());
    }

    @Test
    void envioTemplate_cargaYRenderizaPlantilla() {
        stubComun(true);
        when(plantillaUtil.cargarDesdeClasspath("templates/sms/otp.txt"))
                .thenReturn("Tu código es {{code}}");
        when(plantillaUtil.renderizar(eq("Tu código es {{code}}"), anyMap()))
                .thenReturn("Tu código es 1234");
        when(smsProvider.enviar(anyMap(), any())).thenReturn(Map.of(
                "messages", List.of(Map.of("messageId", "MSG-T",
                        "status", Map.of("id", 26, "name", "PENDING_ACCEPTED")))));

        SmsRequest.TemplateRef tmpl = new SmsRequest.TemplateRef("SMS", "OTP", 1);
        Map<String, Object> vars = new HashMap<>();
        vars.put("code", "1234");
        SmsRequest req = new SmsRequest(null, "TEMPLATE", tmpl, TO_VALIDO, vars, null, null);

        SmsResponse resp = useCase.enviar(USUARIO_ID, req);

        assertThat(resp.ok()).isTrue();
        verify(plantillaUtil).cargarDesdeClasspath("templates/sms/otp.txt");
        verify(plantillaUtil).renderizar(anyString(), anyMap());
    }

    @Test
    void sinPlanActivo_lanzaLimiteAlcanzadoYRegistraFallo() {
        stubComun(false);

        SmsRequest req = new SmsRequest(null, "INLINE", null, TO_VALIDO, null, "Hola", null);

        assertThatThrownBy(() -> useCase.enviar(USUARIO_ID, req))
                .isInstanceOf(LimiteAlcanzadoException.class);

        verify(smsProvider, never()).enviar(anyMap(), any());
    }

    @Test
    void proveedorCaido_lanzaServicioNoDisponible() {
        stubComun(true);
        when(smsProvider.enviar(anyMap(), any())).thenThrow(new RuntimeException("timeout"));

        SmsRequest req = new SmsRequest(null, "INLINE", null, TO_VALIDO, null, "Hola", null);

        assertThatThrownBy(() -> useCase.enviar(USUARIO_ID, req))
                .isInstanceOf(ServicioNoDisponibleException.class);

        verify(consumoRepository).registrar(eq(USUARIO_ID), eq(FUNCION_ID), anyString(),
                anyString(), eq(false), eq(false), anyString());
    }

    @Test
    void telefonoInvalido_noLlamaProveedor() {
        stubComun(true);

        SmsRequest req = new SmsRequest(null, "INLINE", null, "abc123", null, "Hola", null);

        assertThatThrownBy(() -> useCase.enviar(USUARIO_ID, req))
                .isInstanceOf(RuntimeException.class);

        verifyNoInteractions(smsProvider);
    }

    @Test
    void modoTemplateSinTemplate_lanzaIllegalArgument() {
        stubComun(true);

        SmsRequest req = new SmsRequest(null, "TEMPLATE", null, TO_VALIDO, null, null, null);

        assertThatThrownBy(() -> useCase.enviar(USUARIO_ID, req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("template");

        verifyNoInteractions(smsProvider);
    }

    // ------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------

    private void stubComun(boolean conPlanActivo) {
        when(usuarioRepository.obtenerNombrePorId(USUARIO_ID)).thenReturn("USUARIO TEST");

        Map<String, Object> config = new HashMap<>();
        config.put("ApiServicesFuncionId", FUNCION_ID);
        config.put("EndpointExterno", "https://api.fake/sms");
        config.put("Token", "TOKEN_EN_CLARO");
        config.put("Autorizacion", "App {TOKEN}");
        when(smsRepository.resolverConfiguracion(USUARIO_ID)).thenReturn(config);

        Map<String, Object> validacion = new HashMap<>();
        validacion.put("NombrePlan", conPlanActivo ? "PROFESIONAL" : "");
        validacion.put("ConsumoActual", 5);
        validacion.put("LimiteMaximo", 100);
        validacion.put("PuedeContinuar", conPlanActivo ? 1 : 0);
        when(consumoRepository.validarLimitePlan(USUARIO_ID, FUNCION_ID)).thenReturn(validacion);
    }
}
