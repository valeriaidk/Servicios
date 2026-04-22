package pe.extech.utilitarios.modules.auth.application.usecases;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import pe.extech.utilitarios.exception.UsuarioInactivoException;
import pe.extech.utilitarios.modules.auth.domain.repository.AuthConfigRepository;
import pe.extech.utilitarios.modules.auth.dto.AuthResponse;
import pe.extech.utilitarios.modules.auth.dto.LoginRequest;
import pe.extech.utilitarios.modules.auth.dto.RegistroRequest;
import pe.extech.utilitarios.modules.user.domain.repository.ConsumoRepository;
import pe.extech.utilitarios.modules.user.domain.repository.TokenRepository;
import pe.extech.utilitarios.modules.user.domain.repository.UsuarioRepository;
import pe.extech.utilitarios.security.ApiKeyUtil;
import pe.extech.utilitarios.security.JwtUtil;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Pruebas unitarias de {@link AuthUseCasesImpl} — flujo de registro y login.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AuthUseCasesImplTest {

    @Mock private AuthConfigRepository authRepository;
    @Mock private UsuarioRepository usuarioRepository;
    @Mock private TokenRepository tokenRepository;
    @Mock private ConsumoRepository consumoRepository;
    @Mock private JwtUtil jwtUtil;
    @Mock private ApiKeyUtil apiKeyUtil;
    @Mock private BCryptPasswordEncoder passwordEncoder;

    private AuthUseCasesImpl useCase;

    @BeforeEach
    void setUp() {
        useCase = new AuthUseCasesImpl(authRepository, usuarioRepository, tokenRepository,
                consumoRepository, jwtUtil, apiKeyUtil, passwordEncoder);
    }

    @Test
    void registro_exitoso_retornaUsuarioYApiKeyTextoPlano() {
        when(passwordEncoder.encode(anyString())).thenReturn("HASH");
        when(usuarioRepository.guardarOActualizar(anyString(), anyString(), anyString(),
                anyString(), any(), any(), any())).thenReturn(Map.of(
                        "UsuarioId", 42, "PlanId", 1));
        when(apiKeyUtil.generar()).thenReturn(new ApiKeyUtil.ApiKeyGenerado("PLAIN_KEY", "BCRYPT_HASH"));

        RegistroRequest req = new RegistroRequest("Juan", "Perez",
                "juan@extech.pe", "Secret12345", "+51999000111", null, null);

        AuthResponse resp = useCase.registrar(req);

        assertThat(resp.ok()).isTrue();
        assertThat(resp.apiKey()).isEqualTo("PLAIN_KEY");
        assertThat(resp.usuario().usuarioId()).isEqualTo(42);
        verify(tokenRepository).insertar(eq(42), eq("BCRYPT_HASH"),
                any(LocalDateTime.class), any(LocalDateTime.class));
    }

    @Test
    void registro_correoDuplicado_lanzaIllegalArgument() {
        when(passwordEncoder.encode(anyString())).thenReturn("HASH");
        when(usuarioRepository.guardarOActualizar(anyString(), anyString(), anyString(),
                anyString(), any(), any(), any()))
                .thenThrow(new RuntimeException("El correo ya existe."));

        RegistroRequest req = new RegistroRequest("J", "P", "juan@extech.pe", "x", null, null, null);

        assertThatThrownBy(() -> useCase.registrar(req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ya está registrado");
    }

    @Test
    void login_exitoso_retornaJwt() {
        Map<String, Object> usuario = new HashMap<>();
        usuario.put("UsuarioId", 1);
        usuario.put("Nombre", "Juan");
        usuario.put("Apellido", "Pérez");
        usuario.put("PasswordHash", "HASH");
        usuario.put("Activo", 1);
        when(authRepository.obtenerPorEmail("juan@extech.pe")).thenReturn(usuario);
        when(passwordEncoder.matches("Secret12345", "HASH")).thenReturn(true);
        when(authRepository.obtenerPlanActivo(1)).thenReturn(Map.of("PlanId", 2, "Nombre", "PRO"));
        when(jwtUtil.generar(1, 2, "juan@extech.pe")).thenReturn("JWT_TOKEN");
        when(consumoRepository.obtenerTotalMensual(1, null)).thenReturn(15);

        AuthResponse resp = useCase.login(new LoginRequest("juan@extech.pe", "Secret12345"));

        assertThat(resp.ok()).isTrue();
        assertThat(resp.jwt()).isEqualTo("JWT_TOKEN");
        assertThat(resp.plan().planId()).isEqualTo(2);
        assertThat(resp.plan().plan()).isEqualTo("PRO");
        assertThat(resp.plan().consumoActual()).isEqualTo(15);
    }

    @Test
    void login_credencialesInvalidas_lanzaIllegalArgument() {
        when(authRepository.obtenerPorEmail("x@y.com")).thenReturn(Map.of());

        assertThatThrownBy(() -> useCase.login(new LoginRequest("x@y.com", "pwd")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Credenciales inválidas");
    }

    @Test
    void login_passwordIncorrecto_lanzaIllegalArgument() {
        Map<String, Object> usuario = new HashMap<>();
        usuario.put("UsuarioId", 1);
        usuario.put("PasswordHash", "HASH");
        usuario.put("Activo", 1);
        when(authRepository.obtenerPorEmail("juan@extech.pe")).thenReturn(usuario);
        when(passwordEncoder.matches("wrong", "HASH")).thenReturn(false);

        assertThatThrownBy(() -> useCase.login(new LoginRequest("juan@extech.pe", "wrong")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void login_usuarioInactivo_lanzaUsuarioInactivo() {
        Map<String, Object> usuario = new HashMap<>();
        usuario.put("UsuarioId", 1);
        usuario.put("PasswordHash", "HASH");
        usuario.put("Activo", 0);
        when(authRepository.obtenerPorEmail("juan@extech.pe")).thenReturn(usuario);
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(true);

        assertThatThrownBy(() -> useCase.login(new LoginRequest("juan@extech.pe", "x")))
                .isInstanceOf(UsuarioInactivoException.class);
    }
}
