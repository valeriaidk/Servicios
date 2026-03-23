package pe.extech.utilitarios.util;

/**
 * Resultado de la validación de plan (uspPlanValidarLimiteUsuario).
 *
 * Devuelve el estado del consumo ANTES de registrar el request actual.
 * Para incluir en la respuesta al cliente, usar consumoActual + 1.
 *
 * limiteMaximo es nullable: null significa sin límite (ej: ENTERPRISE).
 */
public record PlanContext(
        String plan,
        int consumoActual,
        Integer limiteMaximo
) {}
