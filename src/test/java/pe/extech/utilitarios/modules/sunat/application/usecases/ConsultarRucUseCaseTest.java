package pe.extech.utilitarios.modules.sunat.application.usecases;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import pe.extech.utilitarios.exception.LimiteAlcanzadoException;
import pe.extech.utilitarios.exception.ServicioNoDisponibleException;
import pe.extech.utilitarios.modules.sunat.domain.ports.SunatProvider;
import pe.extech.utilitarios.modules.sunat.domain.repository.SunatConfigRepository;
import pe.extech.utilitarios.modules.sunat.dto.SunatResponse;
import pe.extech.utilitarios.modules.sunat.infrastructure.mapper.DecolectaSunatMapper;
import pe.extech.utilitarios.modules.sunat.infrastructure.provider.SunatProviderFactory;
import pe.extech.utilitarios.modules.user.domain.repository.ConsumoRepository;
import pe.extech.utilitarios.modules.user.domain.repository.UsuarioRepository;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Pruebas unitarias del caso de uso {@link ConsultarRucUseCase}.
 *
 * <p>
 * Usa Mockito para simular los repositorios concretos
 * ({@link UsuarioRepository}, {@link ConsumoRepository}) y el puerto
 * {@link SunatProvider}. Valida los escenarios principales: éxito,
 * sin plan activo, proveedor caído y RUC inválido.
 * </p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ConsultarRucUseCaseTest {

        private static final int USUARIO_ID = 1;
        private static final int FUNCION_ID = 10;
        private static final String RUC_VALIDO = "20100070970";

        @Mock
        private SunatConfigRepository sunatRepository;
        @Mock
        private ConsumoRepository consumoRepository;
        @Mock
        private UsuarioRepository usuarioRepository;
        @Mock
        private SunatProvider sunatProvider;

        @Spy
        private ObjectMapper objectMapper = new ObjectMapper();

        private DecolectaSunatMapper mapper;
        private ConsultarRucUseCase useCase;

        @BeforeEach
        void setUp() {
                mapper = new DecolectaSunatMapper(objectMapper);
                SunatProviderFactory factory = new SunatProviderFactory(List.of(sunatProvider));
                useCase = new ConsultarRucUseCase(
                                sunatRepository, consumoRepository, usuarioRepository,
                                factory, mapper, objectMapper);
        }

        @Test
        void consultaExitosa_retornaResponseYRegistraConsumoExitoso() {
                stubDependenciasComunes(true);
                when(sunatProvider.consultar(anyMap(), eq(RUC_VALIDO)))
                                .thenReturn(Map.of(
                                                "razon_social", "EXTECH SAC",
                                                "estado", "ACTIVO",
                                                "condicion", "HABIDO"));

                SunatResponse resp = useCase.consultarRuc(USUARIO_ID, RUC_VALIDO);

                assertThat(resp.ok()).isTrue();
                assertThat(resp.data().razonSocial()).isEqualTo("EXTECH SAC");
                assertThat(resp.data().estado()).isEqualTo("ACTIVO");
                verify(consumoRepository).registrar(eq(USUARIO_ID), eq(FUNCION_ID), anyString(),
                                anyString(), eq(true), eq(true), anyString());
        }

        @Test
        void sinPlanActivo_lanzaLimiteAlcanzadoYRegistraFallo() {
                stubDependenciasComunes(false); // sin plan

                assertThatThrownBy(() -> useCase.consultarRuc(USUARIO_ID, RUC_VALIDO))
                                .isInstanceOf(LimiteAlcanzadoException.class)
                                .hasMessageContaining("plan activo");

                verify(sunatProvider, never()).consultar(anyMap(), anyString());
                verify(consumoRepository).registrar(eq(USUARIO_ID), eq(FUNCION_ID), anyString(),
                                anyString(), eq(false), eq(true), anyString());
        }

        @Test
        void proveedorCaido_lanzaServicioNoDisponibleYRegistraFallo() {
                stubDependenciasComunes(true);
                when(sunatProvider.consultar(anyMap(), eq(RUC_VALIDO)))
                                .thenThrow(new RuntimeException("timeout"));

                assertThatThrownBy(() -> useCase.consultarRuc(USUARIO_ID, RUC_VALIDO))
                                .isInstanceOf(ServicioNoDisponibleException.class);

                verify(consumoRepository).registrar(eq(USUARIO_ID), eq(FUNCION_ID), anyString(),
                                anyString(), eq(false), eq(true), anyString());
        }

        @Test
        void rucInvalido_noLlamaProveedorNiRegistraConsumo() {
                when(usuarioRepository.obtenerNombrePorId(USUARIO_ID)).thenReturn("USUARIO TEST");

                assertThatThrownBy(() -> useCase.consultarRuc(USUARIO_ID, "123"))
                                .isInstanceOf(RuntimeException.class);

                verifyNoInteractions(sunatProvider);
                verify(consumoRepository, never()).registrar(anyInt(), anyInt(), anyString(),
                                anyString(), anyBoolean(), anyBoolean(), anyString());
        }

        // ------------------------------------------------------------------------
        // Helpers
        // ------------------------------------------------------------------------

        private void stubDependenciasComunes(boolean conPlanActivo) {
                when(usuarioRepository.obtenerNombrePorId(USUARIO_ID)).thenReturn("USUARIO TEST");

                Map<String, Object> config = new HashMap<>();
                config.put("ApiServicesFuncionId", FUNCION_ID);
                config.put("EndpointExterno", "https://api.fake/sunat?numero=");
                config.put("Token", "TOKEN_EN_CLARO");
                config.put("Autorizacion", "Bearer {TOKEN}");
                when(sunatRepository.resolverConfiguracion(USUARIO_ID)).thenReturn(config);

                Map<String, Object> validacion = new HashMap<>();
                validacion.put("NombrePlan", conPlanActivo ? "PROFESIONAL" : "");
                validacion.put("ConsumoActual", 10);
                validacion.put("LimiteMaximo", 1000);
                validacion.put("PuedeContinuar", conPlanActivo ? 1 : 0);
                when(consumoRepository.validarLimitePlan(USUARIO_ID, FUNCION_ID)).thenReturn(validacion);
        }
}
