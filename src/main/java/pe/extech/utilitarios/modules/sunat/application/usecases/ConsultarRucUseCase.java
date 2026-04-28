package pe.extech.utilitarios.modules.sunat.application.usecases;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import pe.extech.utilitarios.exception.LimiteAlcanzadoException;
import pe.extech.utilitarios.exception.ProveedorExternoException;
import pe.extech.utilitarios.exception.ServicioNoDisponibleException;
import pe.extech.utilitarios.modules.sunat.application.interfaces.ISunatUseCases;
import pe.extech.utilitarios.modules.sunat.domain.repository.SunatConfigRepository;
import pe.extech.utilitarios.modules.sunat.dto.SunatResponse;
import pe.extech.utilitarios.modules.sunat.infrastructure.mapper.DecolectaSunatMapper;
import pe.extech.utilitarios.modules.sunat.infrastructure.provider.SunatProviderFactory;
import pe.extech.utilitarios.modules.user.domain.repository.ConsumoRepository;
import pe.extech.utilitarios.modules.user.domain.repository.UsuarioRepository;
import pe.extech.utilitarios.util.PlanContext;
import pe.extech.utilitarios.util.ValidadorUtil;

import java.util.Map;

/**
 * Caso de uso: consultar un contribuyente por RUC.
 *
 * <p>
 * Orquesta la validación del RUC, la resolución de configuración del
 * proveedor, la verificación del plan y la llamada al proveedor externo
 * a través de {@link SunatProviderFactory}. El mapeo específico del
 * proveedor se delega a {@link DecolectaSunatMapper}.
 * </p>
 *
 * <p>
 * Regla R2: 1 request = 1 registro en {@code IT_Consumo}, tanto en éxito
 * como en fallo del proveedor.
 * </p>
 */
@Slf4j
@Service
public class ConsultarRucUseCase implements ISunatUseCases {

    private final SunatConfigRepository sunatRepository;
    private final ConsumoRepository consumoRepository;
    private final UsuarioRepository usuarioRepository;
    private final SunatProviderFactory providerFactory;
    private final DecolectaSunatMapper mapper;
    private final ObjectMapper objectMapper;

    public ConsultarRucUseCase(SunatConfigRepository sunatRepository,
            ConsumoRepository consumoRepository,
            UsuarioRepository usuarioRepository,
            SunatProviderFactory providerFactory,
            DecolectaSunatMapper mapper,
            ObjectMapper objectMapper) {
        this.sunatRepository = sunatRepository;
        this.consumoRepository = consumoRepository;
        this.usuarioRepository = usuarioRepository;
        this.providerFactory = providerFactory;
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public SunatResponse consultarRuc(int usuarioId, String rucParam) {

        // 1. Validaciones previas
        String nombreUsuario = usuarioRepository.obtenerNombrePorId(usuarioId);
        ValidadorUtil.validarRuc(rucParam);

        // 2. Configuración del proveedor y preparación del payload
        Map<String, Object> config = sunatRepository.resolverConfiguracion(usuarioId);
        int funcionId = ((Number) config.get("ApiServicesFuncionId")).intValue();
        String payload = "{\"numero\":\"" + rucParam + "\"}";

        // 3. Verificación del plan (consume registro si falla el límite)
        PlanContext plan = verificarLimite(usuarioId, funcionId, payload, nombreUsuario);

        // 4. Llamada al proveedor con fallback manejado por la fábrica
        Map<String, Object> externa;
        try {
            externa = providerFactory.consultar(config, rucParam);

        } catch (LimiteAlcanzadoException e) {
            throw e;

        } catch (ProveedorExternoException e) {
            String responseJson = "{\"httpStatus\":" + e.getStatusProveedor()
                    + ",\"providerBody\":" + safeJson(e.getBodyProveedor()) + "}";
            consumoRepository.registrar(usuarioId, funcionId, payload, responseJson,
                    false, true, nombreUsuario);
            throw e;

        } catch (Exception e) {
            log.error("[SUNAT] Error inesperado consultando RUC {}: {}", rucParam, e.getMessage(), e);
            consumoRepository.registrar(usuarioId, funcionId, payload,
                    "{\"error\":\"" + e.getMessage() + "\"}", false, true, nombreUsuario);
            throw new ServicioNoDisponibleException("Decolecta-SUNAT");
        }

        // 5. Mapeo y registro de consumo exitoso
        SunatResponse respuesta = mapper.mapear(externa, rucParam, usuarioId,
                nombreUsuario, plan, funcionId);

        consumoRepository.registrar(usuarioId, funcionId, payload, toJson(respuesta),
                true, true, nombreUsuario);

        return respuesta;
    }

    // ------------------------------------------------------------------------
    // Helpers privados
    // ------------------------------------------------------------------------

    private PlanContext verificarLimite(int usuarioId, int funcionId,
            String payload, String nombreUsuario) {

        Map<String, Object> resultado = consumoRepository.validarLimitePlan(usuarioId, funcionId);

        String nombrePlan = (String) resultado.getOrDefault("NombrePlan", "");
        if (nombrePlan == null || nombrePlan.isBlank()) {
            consumoRepository.registrar(usuarioId, funcionId, payload,
                    "Usuario sin plan activo.", false, true, nombreUsuario);
            throw new LimiteAlcanzadoException(
                    "No tienes un plan activo. Contáctate con soporte.", 0, 0, "SIN_PLAN");
        }

        int consumoActual = resultado.get("ConsumoActual") != null
                ? ((Number) resultado.get("ConsumoActual")).intValue()
                : 0;
        Integer limiteMaximo = resultado.get("LimiteMaximo") != null
                ? ((Number) resultado.get("LimiteMaximo")).intValue()
                : null;

        if (!ValidadorUtil.bit(resultado.get("PuedeContinuar"))) {
            int lim = limiteMaximo != null ? limiteMaximo : 0;
            String msg = (String) resultado.getOrDefault("MensajeError", "Límite alcanzado.");
            consumoRepository.registrar(usuarioId, funcionId, payload, msg,
                    false, true, nombreUsuario);
            throw new LimiteAlcanzadoException(msg, consumoActual, lim, nombrePlan);
        }

        return new PlanContext(nombrePlan, consumoActual, limiteMaximo);
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return obj != null ? obj.toString() : null;
        }
    }

    private String safeJson(String rawBody) {
        if (rawBody == null || rawBody.isBlank()) {
            return "\"\"";
        }
        String trimmed = rawBody.trim();
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            return trimmed;
        }
        try {
            return objectMapper.writeValueAsString(rawBody);
        } catch (Exception e) {
            return "\"\"";
        }
    }
}
