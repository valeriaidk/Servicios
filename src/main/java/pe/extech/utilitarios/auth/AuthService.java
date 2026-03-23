package pe.extech.utilitarios.auth;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import pe.extech.utilitarios.auth.dto.AuthResponse;
import pe.extech.utilitarios.auth.dto.LoginRequest;
import pe.extech.utilitarios.auth.dto.RegistroRequest;
import pe.extech.utilitarios.domain.consumo.ConsumoRepository;
import pe.extech.utilitarios.domain.token.TokenRepository;
import pe.extech.utilitarios.domain.usuario.UsuarioRepository;
import pe.extech.utilitarios.exception.UsuarioInactivoException;
import pe.extech.utilitarios.security.ApiKeyUtil;
import pe.extech.utilitarios.security.JwtUtil;
import pe.extech.utilitarios.util.ValidadorUtil;

import java.time.LocalDateTime;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final AuthRepository authRepository;
    private final UsuarioRepository usuarioRepository;
    private final TokenRepository tokenRepository;
    private final ConsumoRepository consumoRepository;
    private final JwtUtil jwtUtil;
    private final ApiKeyUtil apiKeyUtil;
    private final BCryptPasswordEncoder passwordEncoder;

    /**
     * Registra un nuevo usuario:
     * 1. Verifica email único
     * 2. Hashea contraseña con BCrypt
     * 3. Crea usuario + asigna plan FREE (vía SP)
     * 4. Genera API Key → guarda solo el hash BCrypt en BD
     * 5. Retorna apiKey en texto plano SOLO en esta respuesta (no recuperable después)
     */
    public AuthResponse registrar(RegistroRequest req) {
        if (authRepository.existeEmail(req.email())) {
            throw new IllegalArgumentException(
                    "El correo " + req.email() + " ya está registrado.");
        }

        String passwordHash = passwordEncoder.encode(req.password());

        Map<String, Object> resultado = usuarioRepository.guardarOActualizar(
                req.nombre(), req.apellido(), req.email(), passwordHash,
                req.telefono(), req.razonSocial(), req.ruc());

        int usuarioId = ((Number) resultado.get("UsuarioId")).intValue();
        int planId = ((Number) resultado.get("PlanId")).intValue();

        // Genera API Key — almacena solo hash BCrypt (R8: nunca texto plano en BD)
        ApiKeyUtil.ApiKeyGenerado apiKey = apiKeyUtil.generar();
        tokenRepository.insertar(usuarioId, apiKey.hash(),
                LocalDateTime.now(), LocalDateTime.now().plusYears(1));

        log.info("Usuario registrado: id={}", usuarioId);

        return new AuthResponse(
                true, null, apiKey.plano(),
                new AuthResponse.UsuarioDto(usuarioId, req.nombre(), req.apellido(), req.email()),
                new AuthResponse.PlanDto(planId, "FREE", 0),
                "Registro exitoso. Guarda tu API Key: no se mostrará nuevamente."
        );
    }

    /**
     * Login:
     * 1. Valida email y contraseña
     * 2. Genera JWT (userId + planId)
     * 3. El API Key NO se retorna en el login (hash BCrypt no es reversible).
     *    Si se necesita, regenerar vía POST /usuario/api-key/regenerar.
     */
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

        // BIT de SQL Server llega como Boolean vía MS JDBC; usar helper defensivo
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
                null
        );
    }

    private int obtenerPlanId(int usuarioId) {
        Map<String, Object> plan = authRepository.obtenerPlanActivo(usuarioId);
        if (plan == null || plan.isEmpty()) return 1;
        return ((Number) plan.get("PlanId")).intValue();
    }
}
