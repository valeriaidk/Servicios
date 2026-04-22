package pe.extech.utilitarios.modules.auth.application.usecases;

import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import pe.extech.utilitarios.exception.UsuarioInactivoException;
import pe.extech.utilitarios.modules.auth.application.interfaces.IAuthUseCases;
import pe.extech.utilitarios.modules.auth.domain.repository.AuthConfigRepository;
import pe.extech.utilitarios.modules.auth.dto.AuthResponse;
import pe.extech.utilitarios.modules.auth.dto.LoginRequest;
import pe.extech.utilitarios.modules.auth.dto.RegistroRequest;
import pe.extech.utilitarios.modules.user.domain.repository.ConsumoRepository;
import pe.extech.utilitarios.modules.user.domain.repository.TokenRepository;
import pe.extech.utilitarios.modules.user.domain.repository.UsuarioRepository;
import pe.extech.utilitarios.security.ApiKeyUtil;
import pe.extech.utilitarios.security.JwtUtil;
import pe.extech.utilitarios.util.ValidadorUtil;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Casos de uso de autenticación: registro y login.
 *
 * <p>
 * El API Key solo se retorna en texto plano en el registro — en BD se persiste
 * únicamente su hash BCrypt (R8). Para recuperarlo tras el registro, el usuario
 * debe invocar {@code POST /usuario/api-key/regenerar}.
 * </p>
 */
@Slf4j
@Service
public class AuthUseCasesImpl implements IAuthUseCases {

    private final AuthConfigRepository authRepository;
    private final UsuarioRepository usuarioRepository;
    private final TokenRepository tokenRepository;
    private final ConsumoRepository consumoRepository;
    private final JwtUtil jwtUtil;
    private final ApiKeyUtil apiKeyUtil;
    private final BCryptPasswordEncoder passwordEncoder;

    public AuthUseCasesImpl(AuthConfigRepository authRepository,
            UsuarioRepository usuarioRepository,
            TokenRepository tokenRepository,
            ConsumoRepository consumoRepository,
            JwtUtil jwtUtil,
            ApiKeyUtil apiKeyUtil,
            BCryptPasswordEncoder passwordEncoder) {
        this.authRepository = authRepository;
        this.usuarioRepository = usuarioRepository;
        this.tokenRepository = tokenRepository;
        this.consumoRepository = consumoRepository;
        this.jwtUtil = jwtUtil;
        this.apiKeyUtil = apiKeyUtil;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public AuthResponse registrar(RegistroRequest req) {
        String passwordHash = passwordEncoder.encode(req.password());

        Map<String, Object> resultado;
        try {
            resultado = usuarioRepository.guardarOActualizar(
                    req.nombre(), req.apellido(), req.email(), passwordHash,
                    req.telefono(), req.razonSocial(), req.ruc());
        } catch (Exception ex) {
            String msg = ex.getMessage() != null ? ex.getMessage() : "";
            if (msg.contains("El correo ya existe")) {
                throw new IllegalArgumentException(
                        "El correo " + req.email() + " ya está registrado.");
            }
            throw new IllegalArgumentException(
                    "No se pudo completar el registro: " + msg);
        }

        int usuarioId = ((Number) resultado.get("UsuarioId")).intValue();
        int planId = ((Number) resultado.get("PlanId")).intValue();

        // Genera API Key — persiste solo hash BCrypt en BD (R8).
        ApiKeyUtil.ApiKeyGenerado apiKey = apiKeyUtil.generar();
        tokenRepository.insertar(usuarioId, apiKey.hash(),
                LocalDateTime.now(), LocalDateTime.now().plusYears(1));

        log.info("Usuario registrado: id={}", usuarioId);

        return new AuthResponse(
                true, null, apiKey.plano(),
                new AuthResponse.UsuarioDto(usuarioId, req.nombre(), req.apellido(), req.email()),
                new AuthResponse.PlanDto(planId, "FREE", 0),
                "Registro exitoso. Guarda tu API Key: no se mostrará nuevamente.");
    }

    @Override
    public AuthResponse login(LoginRequest req) {
        Map<String, Object> usuario = authRepository.obtenerPorEmail(req.email());

        if (usuario == null || usuario.isEmpty()) {
            throw new IllegalArgumentException("Credenciales inválidas.");
        }

        String passwordHash = (String) usuario.get("PasswordHash");
        if (passwordHash == null || passwordHash.isBlank()
                || !passwordEncoder.matches(req.password(), passwordHash)) {
            throw new IllegalArgumentException("Credenciales inválidas.");
        }

        if (!ValidadorUtil.bit(usuario.get("Activo"))) {
            throw new UsuarioInactivoException();
        }

        int usuarioId = ((Number) usuario.get("UsuarioId")).intValue();
        int planId = obtenerPlanId(usuarioId);

        String jwt = jwtUtil.generar(usuarioId, planId, req.email());

        int consumoActual = consumoRepository.obtenerTotalMensual(usuarioId, null);
        Map<String, Object> plan = authRepository.obtenerPlanActivo(usuarioId);
        String nombrePlan = plan.containsKey("Nombre") ? (String) plan.get("Nombre") : "FREE";

        return new AuthResponse(
                true, jwt, null,
                new AuthResponse.UsuarioDto(usuarioId,
                        (String) usuario.get("Nombre"),
                        (String) usuario.get("Apellido"),
                        req.email()),
                new AuthResponse.PlanDto(planId, nombrePlan, consumoActual),
                null);
    }

    private int obtenerPlanId(int usuarioId) {
        Map<String, Object> plan = authRepository.obtenerPlanActivo(usuarioId);
        if (plan == null || plan.isEmpty()) {
            return 1;
        }
        return ((Number) plan.get("PlanId")).intValue();
    }
}
