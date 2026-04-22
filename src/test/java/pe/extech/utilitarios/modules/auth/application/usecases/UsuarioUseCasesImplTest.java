package pe.extech.utilitarios.modules.auth.application.usecases;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import pe.extech.utilitarios.modules.auth.domain.repository.AuthConfigRepository;
import pe.extech.utilitarios.modules.user.domain.repository.ConsumoRepository;
import pe.extech.utilitarios.modules.user.domain.repository.PlanRepository;
import pe.extech.utilitarios.modules.user.domain.repository.TokenRepository;
import pe.extech.utilitarios.security.ApiKeyUtil;
import pe.extech.utilitarios.security.JwtUtil;

import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pruebas unitarias de {@link UsuarioUseCasesImpl} — operaciones del usuario
 * autenticado (perfil, api key, plan, consumo).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UsuarioUseCasesImplTest {

    @Mock private AuthConfigRepository authRepository;
    @Mock private TokenRepository tokenRepository;
    @Mock private ConsumoRepository consumoRepository;
    @Mock private PlanRepository planRepository;
    @Mock private ApiKeyUtil apiKeyUtil;
    @Mock private JwtUtil jwtUtil;

    private UsuarioUseCasesImpl useCase;

    @BeforeEach
    void setUp() {
        useCase = new UsuarioUseCasesImpl(authRepository, tokenRepository, consumoRepository,
                planRepository, apiKeyUtil, jwtUtil);
    }

    @Test
    void obtenerPerfil_combinaUsuarioYPlan() {
        when(authRepository.obtenerDatosUsuario(1)).thenReturn(Map.of(
                "UsuarioId", 1, "Nombre", "Juan", "Apellido", "Pérez",
                "Email", "juan@extech.pe", "Activo", true,
                "FechaRegistro", "2026-04-22"));
        when(authRepository.obtenerPlanActivo(1)).thenReturn(Map.of(
                "PlanId", 3, "Nombre", "PRO"));

        Map<String, Object> perfil = useCase.obtenerPerfil(1);

        assertThat(perfil).containsEntry("nombre", "Juan");
        assertThat(perfil).containsEntry("planId", 3);
        assertThat(perfil).containsEntry("plan", "PRO");
    }

    @Test
    void regenerarApiKey_desactivaYCreaNuevo() {
        when(apiKeyUtil.generar()).thenReturn(new ApiKeyUtil.ApiKeyGenerado("NEW_PLAIN", "NEW_HASH"));

        String nuevo = useCase.regenerarApiKey(1);

        assertThat(nuevo).isEqualTo("NEW_PLAIN");
        verify(tokenRepository).desactivarYCrear(eq(1), eq("NEW_HASH"),
                any(LocalDateTime.class), any(LocalDateTime.class));
    }

    @Test
    void cambiarPlan_emiteNuevoJwt() {
        when(planRepository.cambiarPlan(eq(1), eq(2), any(), eq(1)))
                .thenReturn(Map.of("PlanId", 2, "NombrePlan", "PRO"));
        when(jwtUtil.generar(1, 2, "juan@extech.pe")).thenReturn("NEW_JWT");

        Map<String, Object> resultado = useCase.cambiarPlan(1, 2, "juan@extech.pe");

        assertThat(resultado).containsEntry("ok", true);
        assertThat(resultado).containsEntry("nuevoJwt", "NEW_JWT");
        assertThat(resultado).containsEntry("nuevoPlan", "PRO");
    }

    @Test
    void obtenerResumenConsumo_incluyeLimiteCuandoHayPlan() {
        when(consumoRepository.obtenerTotalMensual(1, null)).thenReturn(42);
        when(authRepository.obtenerPlanActivo(1)).thenReturn(Map.of(
                "PlanId", 2, "Nombre", "PRO"));
        when(authRepository.obtenerLimiteMensualPlan(2)).thenReturn(1000);

        Map<String, Object> resumen = useCase.obtenerResumenConsumo(1);

        assertThat(resumen).containsEntry("ok", true);
        assertThat(resumen).containsEntry("consumoActual", 42);
        assertThat(resumen).containsEntry("plan", "PRO");
        assertThat(resumen).containsEntry("limiteMaximo", 1000);
    }

    @Test
    void obtenerResumenConsumo_sinPlan_retornaFreePorDefecto() {
        when(consumoRepository.obtenerTotalMensual(1, null)).thenReturn(3);
        when(authRepository.obtenerPlanActivo(1)).thenReturn(Map.of());

        Map<String, Object> resumen = useCase.obtenerResumenConsumo(1);

        assertThat(resumen).containsEntry("consumoActual", 3);
        assertThat(resumen).containsEntry("plan", "FREE");
        assertThat(resumen).doesNotContainKey("limiteMaximo");
    }
}
