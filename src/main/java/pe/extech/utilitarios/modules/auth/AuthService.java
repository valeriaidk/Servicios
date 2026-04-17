package pe.extech.utilitarios.modules.auth;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import pe.extech.utilitarios.exception.UsuarioInactivoException;
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
     * 1. Hashea contraseña con BCrypt
     * 2. Crea usuario + asigna plan FREE vía uspIT_UsuarioGuardarActulizar
     * → El SP valida email único internamente y lanza RAISERROR('El correo ya
     * existe.')
     * si hay duplicado. Se captura aquí y se convierte en IllegalArgumentException.
     * → No se hace SELECT previo a IT_Usuario: el SP es la fuente de verdad.
     * 3. Genera API Key → guarda solo el hash BCrypt en BD
     * 4. Retorna apiKey en texto plano SOLO en esta respuesta (no recuperable
     * después)
     */
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

        // Genera API Key — almacena solo hash BCrypt (R8: nunca texto plano en BD)
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

    /**
     * Login:
     * 1. Valida email y contraseña
     * 2. Genera JWT (userId + planId)
     * 3. El API Key NO se retorna en el login (hash BCrypt no es reversible).
     * Si se necesita, regenerar vía POST /usuario/api-key/regenerar.
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
                null);
    }

    private int obtenerPlanId(int usuarioId) {
        Map<String, Object> plan = authRepository.obtenerPlanActivo(usuarioId);
        if (plan == null || plan.isEmpty())
            return 1;
        return ((Number) plan.get("PlanId")).intValue();
    }
}
