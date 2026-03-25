package pe.extech.utilitarios.auth;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import pe.extech.utilitarios.domain.consumo.ConsumoRepository;
import pe.extech.utilitarios.domain.plan.PlanRepository;
import pe.extech.utilitarios.domain.token.TokenRepository;
import pe.extech.utilitarios.security.ApiKeyUtil;
import pe.extech.utilitarios.security.JwtUtil;

import java.time.LocalDateTime;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class UsuarioService {

    private final AuthRepository authRepository;
    private final TokenRepository tokenRepository;
    private final ConsumoRepository consumoRepository;
    private final PlanRepository planRepository;
    private final ApiKeyUtil apiKeyUtil;
    private final JwtUtil jwtUtil;

    /**
     * Perfil completo del usuario autenticado.
     * Combina datos personales (IT_Usuario) con el plan activo (IT_PlanUsuario).
     * Campos retornados: usuarioId, nombre, apellido, email, activo,
     *                    fechaRegistro, planId, plan (nombre del plan).
     */
    public Map<String, Object> obtenerPerfil(int usuarioId) {
        Map<String, Object> usuario = authRepository.obtenerDatosUsuario(usuarioId);
        Map<String, Object> plan   = authRepository.obtenerPlanActivo(usuarioId);

        java.util.Map<String, Object> perfil = new java.util.HashMap<>();
        // Datos del usuario
        perfil.put("usuarioId",      usuario.get("UsuarioId"));
        perfil.put("nombre",         usuario.get("Nombre"));
        perfil.put("apellido",       usuario.get("Apellido"));
        perfil.put("email",          usuario.get("Email"));
        perfil.put("activo",         usuario.get("Activo"));
        perfil.put("fechaRegistro",  usuario.get("FechaRegistro"));
        // Datos del plan
        perfil.put("planId",         plan.get("PlanId"));
        perfil.put("plan",           plan.get("Nombre"));
        return perfil;
    }

    /**
     * Regenera el API Key SOLO cuando el usuario lo solicita explícitamente.
     * Desactiva el anterior (uspApiKeyDesactivarYCrear) e inserta uno nuevo.
     * Retorna el nuevo valor plano para entregarlo al usuario una única vez.
     */
    public String regenerarApiKey(int usuarioId) {
        ApiKeyUtil.ApiKeyGenerado nuevo = apiKeyUtil.generar();
        tokenRepository.desactivarYCrear(usuarioId, nuevo.hash(),
                LocalDateTime.now(), LocalDateTime.now().plusYears(1));
        log.info("API Key regenerado para usuario: {}", usuarioId);
        return nuevo.plano();
    }

    /**
     * Cambia el plan del usuario y genera un nuevo JWT con el planId actualizado.
     */
    public Map<String, Object> cambiarPlan(int usuarioId, int nuevoPlanId, String email) {
        Map<String, Object> resultado = planRepository.cambiarPlan(
                usuarioId, nuevoPlanId, "Cambio de plan solicitado por usuario", usuarioId);

        int planId = ((Number) resultado.get("PlanId")).intValue();
        String nuevoJwt = jwtUtil.generar(usuarioId, planId, email);

        return Map.of(
                "ok", true,
                "nuevoPlan", resultado.get("NombrePlan"),
                "nuevoJwt", nuevoJwt
        );
    }

    /** Historial paginado de consumos */
    public Map<String, Object> obtenerHistorial(int usuarioId, int page, int size) {
        return consumoRepository.obtenerHistorial(usuarioId, page, size);
    }

    /**
     * Resumen del consumo mensual actual vs límite del plan.
     * Campos retornados: ok, consumoActual, plan, limiteMaximo (null si sin límite).
     */
    public Map<String, Object> obtenerResumenConsumo(int usuarioId) {
        int totalMensual = consumoRepository.obtenerTotalMensual(usuarioId, null);
        Map<String, Object> plan = authRepository.obtenerPlanActivo(usuarioId);

        java.util.Map<String, Object> resultado = new java.util.HashMap<>();
        resultado.put("ok", true);
        resultado.put("consumoActual", totalMensual);
        resultado.put("plan", plan.getOrDefault("Nombre", "FREE"));

        // Límite mensual del plan (null = sin límite, ej: ENTERPRISE)
        Object planId = plan.get("PlanId");
        if (planId != null) {
            Integer limite = authRepository.obtenerLimiteMensualPlan(((Number) planId).intValue());
            resultado.put("limiteMaximo", limite);
        }

        return resultado;
    }
}
