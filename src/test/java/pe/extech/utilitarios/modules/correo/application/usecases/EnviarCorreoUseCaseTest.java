package pe.extech.utilitarios.modules.correo.application.usecases;

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
import pe.extech.utilitarios.modules.correo.domain.ports.CorreoProvider;
import pe.extech.utilitarios.modules.correo.domain.repository.CorreoConfigRepository;
import pe.extech.utilitarios.modules.correo.dto.CorreoRequest;
import pe.extech.utilitarios.modules.correo.dto.CorreoResponse;
import pe.extech.utilitarios.modules.correo.infrastructure.provider.CorreoProviderFactory;
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
 * Pruebas unitarias del caso de uso {@link EnviarCorreoUseCase}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EnviarCorreoUseCaseTest {

    private static final int USUARIO_ID = 1;
    private static final int FUNCION_ID = 30;
    private static final String CORREO_VALIDO = "usuario@empresa.com";

    @Mock private CorreoConfigRepository correoRepository;
    @Mock private ConsumoRepository consumoRepository;
    @Mock private UsuarioRepository usuarioRepository;
    @Mock private CorreoProvider correoProvider;
    @Mock private PlantillaUtil plantillaUtil;

    private EnviarCorreoUseCase useCase;

    @BeforeEach
    void setUp() {
        CorreoProviderFactory factory = new CorreoProviderFactory(List.of(correoProvider));
        useCase = new EnviarCorreoUseCase(
                correoRepository, consumoRepository, usuarioRepository,
                factory, plantillaUtil, new ObjectMapper());
    }

    @Test
    void envioInlineExitoso_retornaResponseConReferencia() {
        stubComun(true);
        when(correoProvider.enviar(anyMap(), any())).thenReturn("GRAPH_123");

        CorreoRequest req = new CorreoRequest(
                "EMAIL.SEND", "INLINE", null, List.of(CORREO_VALIDO),
                null, "Asunto", "<p>Hola</p>", null);

        CorreoResponse resp = useCase.enviar(USUARIO_ID, req);

        assertThat(resp.ok()).isTrue();
        assertThat(resp.data().providerReference()).isEqualTo("GRAPH_123");
        assertThat(resp.data().recipients()).containsExactly(CORREO_VALIDO);
        verify(consumoRepository).registrar(eq(USUARIO_ID), eq(FUNCION_ID), anyString(),
                anyString(), eq(true), eq(false), anyString());
    }

    @Test
    void envioTemplate_cargaPlantillaYResuelveAsunto() {
        stubComun(true);
        when(plantillaUtil.cargarDesdeClasspath("templates/correo/bienvenida.html"))
                .thenReturn("<p>Hola {{name}}</p>");
        when(plantillaUtil.renderizar(eq("<p>Hola {{name}}</p>"), anyMap()))
                .thenReturn("<p>Hola Juan</p>");
        when(correoRepository.obtenerAsuntoTemplate(FUNCION_ID, "BIENVENIDA", null))
                .thenReturn("Bienvenido {{name}}");
        when(plantillaUtil.renderizar(eq("Bienvenido {{name}}"), anyMap()))
                .thenReturn("Bienvenido Juan");
        when(correoProvider.enviar(anyMap(), any())).thenReturn("GRAPH_TMPL");

        CorreoRequest.TemplateRef tmpl = new CorreoRequest.TemplateRef("EMAIL", "BIENVENIDA", null);
        Map<String, Object> vars = new HashMap<>();
        vars.put("name", "Juan");
        CorreoRequest req = new CorreoRequest(
                "EMAIL.SEND", "TEMPLATE", tmpl, List.of(CORREO_VALIDO),
                vars, null, null, null);

        CorreoResponse resp = useCase.enviar(USUARIO_ID, req);

        assertThat(resp.ok()).isTrue();
        assertThat(resp.data().templateCode()).isEqualTo("BIENVENIDA");
        verify(plantillaUtil).cargarDesdeClasspath("templates/correo/bienvenida.html");
    }

    @Test
    void sinPlanActivo_lanzaLimiteYRegistraFallo() {
        stubComun(false);

        CorreoRequest req = new CorreoRequest(
                "EMAIL.SEND", "INLINE", null, List.of(CORREO_VALIDO),
                null, "S", "<p>H</p>", null);

        assertThatThrownBy(() -> useCase.enviar(USUARIO_ID, req))
                .isInstanceOf(LimiteAlcanzadoException.class);

        verifyNoInteractions(correoProvider);
    }

    @Test
    void correoInvalido_noLlamaProveedor() {
        stubComun(true);

        CorreoRequest req = new CorreoRequest(
                "EMAIL.SEND", "INLINE", null, List.of("no-es-correo"),
                null, "S", "<p>H</p>", null);

        assertThatThrownBy(() -> useCase.enviar(USUARIO_ID, req))
                .isInstanceOf(RuntimeException.class);

        verifyNoInteractions(correoProvider);
    }

    @Test
    void proveedorCaido_lanzaServicioNoDisponible() {
        stubComun(true);
        when(correoProvider.enviar(anyMap(), any())).thenThrow(new RuntimeException("graph-down"));

        CorreoRequest req = new CorreoRequest(
                "EMAIL.SEND", "INLINE", null, List.of(CORREO_VALIDO),
                null, "S", "<p>H</p>", null);

        assertThatThrownBy(() -> useCase.enviar(USUARIO_ID, req))
                .isInstanceOf(ServicioNoDisponibleException.class);

        verify(consumoRepository).registrar(eq(USUARIO_ID), eq(FUNCION_ID), anyString(),
                anyString(), eq(false), eq(false), anyString());
    }

    @Test
    void modoInlineSinBodyHtml_lanzaIllegalArgument() {
        stubComun(true);

        CorreoRequest req = new CorreoRequest(
                "EMAIL.SEND", "INLINE", null, List.of(CORREO_VALIDO),
                null, "Asunto", null, null);

        assertThatThrownBy(() -> useCase.enviar(USUARIO_ID, req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("body_html");

        verifyNoInteractions(correoProvider);
    }

    // ------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------

    private void stubComun(boolean conPlanActivo) {
        when(usuarioRepository.obtenerNombrePorId(USUARIO_ID)).thenReturn("USUARIO TEST");
        when(correoRepository.obtenerFuncionId()).thenReturn(FUNCION_ID);
        when(correoRepository.obtenerClientSecretCifrado()).thenReturn("CIFRADO");

        Map<String, Object> validacion = new HashMap<>();
        validacion.put("NombrePlan", conPlanActivo ? "PROFESIONAL" : "");
        validacion.put("ConsumoActual", 2);
        validacion.put("LimiteMaximo", 50);
        validacion.put("PuedeContinuar", conPlanActivo ? 1 : 0);
        when(consumoRepository.validarLimitePlan(USUARIO_ID, FUNCION_ID)).thenReturn(validacion);
    }
}
