package pe.extech.utilitarios.modules.reniec.application.usecases;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import pe.extech.utilitarios.exception.*;
import pe.extech.utilitarios.modules.reniec.application.interfaces.IReniecUseCases;
import pe.extech.utilitarios.modules.reniec.domain.ports.ReniecProvider;
import pe.extech.utilitarios.modules.reniec.domain.repository.ReniecConfigRepository;
import pe.extech.utilitarios.modules.reniec.dto.ReniecResponse;
import pe.extech.utilitarios.modules.reniec.infrastructure.provider.ReniecProviderFactory;
import pe.extech.utilitarios.modules.user.domain.repository.ConsumoRepository;
import pe.extech.utilitarios.modules.user.domain.repository.UsuarioRepository;
import pe.extech.utilitarios.util.PlanContext;
import pe.extech.utilitarios.util.ValidadorUtil;

import java.util.Map;

@Slf4j
@Service
public class GetReniecDataUseCase implements IReniecUseCases {

    private static final String SERVICIO_NOMBRE = "Consulta DNI";
    private static final String SERVICIO_CODIGO = "RENIEC_DNI";
    private static final String SERVICIO_DESCRIPCION = "Consulta de datos por DNI";

    private final ReniecConfigRepository reniecRepository;
    private final ConsumoRepository consumoRepository;
    private final UsuarioRepository usuarioRepository;
    private final ReniecProviderFactory providerFactory;

    public GetReniecDataUseCase(
            ReniecConfigRepository reniecRepository,
            ConsumoRepository consumoRepository,
            UsuarioRepository usuarioRepository,
            ReniecProviderFactory providerFactory) {
        this.reniecRepository = reniecRepository;
        this.consumoRepository = consumoRepository;
        this.usuarioRepository = usuarioRepository;
        this.providerFactory = providerFactory;
    }

    @Override
    public ReniecResponse consultarDni(int usuarioId, String dniParam) {

        // 1. Validaciones previas
        String nombreUsuario = usuarioRepository.obtenerNombrePorId(usuarioId);
        ValidadorUtil.validarDni(dniParam);

        // 2. Configuración + selección de proveedor
        Map<String, Object> config = reniecRepository.resolverConfiguracion(usuarioId);
        int funcionId = ((Number) config.get("ApiServicesFuncionId")).intValue();
        String payload = "{\"numero\":\"" + dniParam + "\"}";

        // 3. Verificación del plan
        PlanContext plan = verificarLimite(usuarioId, funcionId, payload, nombreUsuario);

        // 4. Llamada al proveedor externo
        Map<String, Object> externa;

        try {
            // noinspection unchecked
            externa = providerFactory.consultar(config, dniParam);

        } catch (LimiteAlcanzadoException e) {
            throw e;

        } catch (ProveedorExternoException e) {
            consumoRepository.registrar(usuarioId, funcionId, payload,
                    e.getMessage(), false, true, nombreUsuario);
            throw e;

        } catch (Exception e) {
            log.error("[RENIEC] Error inesperado consultando DNI {}: {}", dniParam, e.getMessage(), e);
            consumoRepository.registrar(usuarioId, funcionId, payload,
                    e.getMessage(), false, true, nombreUsuario);
            throw new ServicioNoDisponibleException("Decolecta-RENIEC");
        }

        // 5. Mapeo y registro de consumo exitoso
        ReniecResponse respuesta = mapearRespuesta(externa, dniParam, usuarioId,
                nombreUsuario, plan, funcionId);

        consumoRepository.registrar(usuarioId, funcionId, payload,
                externa.toString(), true, true, nombreUsuario);

        return respuesta;
    }

    // -------------------------------------------------------------------------
    // Helpers privados
    // -------------------------------------------------------------------------

    private PlanContext verificarLimite(int usuarioId, int funcionId,
            String payload, String nombreUsuario) {

        Map<String, Object> resultado = consumoRepository.validarLimitePlan(usuarioId, funcionId);

        String nombrePlan = (String) resultado.getOrDefault("NombrePlan", "");
        if (nombrePlan.isBlank()) {
            consumoRepository.registrar(usuarioId, funcionId, payload,
                    "Sin plan", false, true, nombreUsuario);
            throw new LimiteAlcanzadoException("Sin plan activo", 0, 0, "SIN_PLAN");
        }

        int consumoActual = ((Number) resultado.getOrDefault("ConsumoActual", 0)).intValue();
        Integer limiteMaximo = resultado.get("LimiteMaximo") != null
                ? ((Number) resultado.get("LimiteMaximo")).intValue()
                : null;

        if (!ValidadorUtil.bit(resultado.get("PuedeContinuar"))) {
            throw new LimiteAlcanzadoException(
                    "Límite alcanzado", consumoActual, limiteMaximo, nombrePlan);
        }

        return new PlanContext(nombrePlan, consumoActual, limiteMaximo);
    }

    private ReniecResponse mapearRespuesta(Map<String, Object> externa, String dni,
            int usuarioId, String nombreUsuario,
            PlanContext plan, int funcionId) {

        if (externa == null)
            throw new ServicioNoDisponibleException("Decolecta-RENIEC");

        String nombres = str(externa, "first_name", "nombres");
        String apellidoPat = str(externa, "first_last_name", "apellidoPaterno");
        String apellidoMat = str(externa, "second_last_name", "apellidoMaterno");

        String nombreCompleto = str(externa, "full_name", "nombreCompleto");
        if (nombreCompleto.isBlank()) {
            nombreCompleto = (nombres + " " + apellidoPat + " " + apellidoMat).trim();
        }

        return new ReniecResponse(
                true, "OK", "Consulta correcta",
                usuarioId, nombreUsuario,
                plan.plan(),
                plan.consumoActual() + 1,
                plan.limiteMaximo(),
                funcionId,
                SERVICIO_NOMBRE,
                SERVICIO_CODIGO,
                SERVICIO_DESCRIPCION,
                new ReniecResponse.ReniecData(
                        dni, nombres, apellidoPat, apellidoMat, nombreCompleto));
    }

    private String str(Map<String, Object> map, String k1, String k2) {
        Object v = map.getOrDefault(k1, map.get(k2));
        return v != null ? v.toString() : "";
    }
}