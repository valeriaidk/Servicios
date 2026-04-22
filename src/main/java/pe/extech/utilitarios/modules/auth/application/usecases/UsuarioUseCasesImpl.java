package pe.extech.utilitarios.modules.auth.application.usecases;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import pe.extech.utilitarios.modules.auth.application.interfaces.IUsuarioUseCases;
import pe.extech.utilitarios.modules.auth.domain.repository.AuthConfigRepository;
import pe.extech.utilitarios.modules.user.domain.repository.ConsumoRepository;
import pe.extech.utilitarios.modules.user.domain.repository.PlanRepository;
import pe.extech.utilitarios.modules.user.domain.repository.TokenRepository;
import pe.extech.utilitarios.security.ApiKeyUtil;
import pe.extech.utilitarios.security.JwtUtil;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * Casos de uso de gestión del usuario autenticado: perfil, rotación de API Key,
 * cambio de plan y consultas de consumo.
 *
 * <p>
 * Mantiene la misma semántica del antiguo {@code UsuarioService}. Sigue siendo
 * candidato a descomposición posterior en PerfilService, ApiKeyService,
 * PlanService y ConsumoService, pero esa separación se deja para una segunda
 * fase para minimizar el riesgo en el path crítico de autenticación.
 * </p>
 */
@Slf4j
@Service
public class UsuarioUseCasesImpl implements IUsuarioUseCases {

    private final AuthConfigRepository authRepository;
    private final TokenRepository tokenRepository;
    private final ConsumoRepository consumoRepository;
    private final PlanRepository planRepository;
    private final ApiKeyUtil apiKeyUtil;
    private final JwtUtil jwtUtil;

    public UsuarioUseCasesImpl(AuthConfigRepository authRepository,
            TokenRepository tokenRepository,
            ConsumoRepository consumoRepository,
            PlanRepository planRepository,
            ApiKeyUtil apiKeyUtil,
            JwtUtil jwtUtil) {
        this.authRepository = authRepository;
        this.tokenRepository = tokenRepository;
        this.consumoRepository = consumoRepository;
        this.planRepository = planRepository;
        this.apiKeyUtil = apiKeyUtil;
        this.jwtUtil = jwtUtil;
    }

    @Override
    public Map<String, Object> obtenerPerfil(int usuarioId) {
        Map<String, Object> usuario = authRepository.obtenerDatosUsuario(usuarioId);
        Map<String, Object> plan = authRepository.obtenerPlanActivo(usuarioId);

        Map<String, Object> perfil = new HashMap<>();
        perfil.put("usuarioId", usuario.get("UsuarioId"));
        perfil.put("nombre", usuario.get("Nombre"));
        perfil.put("apellido", usuario.get("Apellido"));
        perfil.put("email", usuario.get("Email"));
        perfil.put("activo", usuario.get("Activo"));
        perfil.put("fechaRegistro", usuario.get("FechaRegistro"));
        perfil.put("planId", plan.get("PlanId"));
        perfil.put("plan", plan.get("Nombre"));
        return perfil;
    }

    @Override
    public String regenerarApiKey(int usuarioId) {
        ApiKeyUtil.ApiKeyGenerado nuevo = apiKeyUtil.generar();
        tokenRepository.desactivarYCrear(usuarioId, nuevo.hash(),
                LocalDateTime.now(), LocalDateTime.now().plusYears(1));
        log.info("API Key regenerado para usuario: {}", usuarioId);
        return nuevo.plano();
    }

    @Override
    public Map<String, Object> cambiarPlan(int usuarioId, int nuevoPlanId, String email) {
        Map<String, Object> resultado = planRepository.cambiarPlan(
                usuarioId, nuevoPlanId, "Cambio de plan solicitado por usuario", usuarioId);

        int planId = ((Number) resultado.get("PlanId")).intValue();
        String nuevoJwt = jwtUtil.generar(usuarioId, planId, email);

        return Map.of(
                "ok", true,
                "nuevoPlan", resultado.get("NombrePlan"),
                "nuevoJwt", nuevoJwt);
    }

    @Override
    public Map<String, Object> obtenerHistorial(int usuarioId, int page, int size) {
        return consumoRepository.obtenerHistorial(usuarioId, page, size);
    }

    @Override
    public Map<String, Object> obtenerResumenConsumo(int usuarioId) {
        int totalMensual = consumoRepository.obtenerTotalMensual(usuarioId, null);
        Map<String, Object> plan = authRepository.obtenerPlanActivo(usuarioId);

        Map<String, Object> resultado = new HashMap<>();
        resultado.put("ok", true);
        resultado.put("consumoActual", totalMensual);
        resultado.put("plan", plan.getOrDefault("Nombre", "FREE"));

        Object planId = plan.get("PlanId");
        if (planId != null) {
            Integer limite = authRepository.obtenerLimiteMensualPlan(((Number) planId).intValue());
            resultado.put("limiteMaximo", limite);
        }

        return resultado;
    }
}
