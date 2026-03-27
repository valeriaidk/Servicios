package pe.extech.utilitarios.sunat;

import pe.extech.utilitarios.sunat.dto.SunatResponse;

/**
 * Contrato del servicio de consulta SUNAT.
 *
 * Implementación actual: {@link SunatService} — consulta el RUC mediante el proveedor
 * Decolecta. Retorna razón social, estado tributario, condición (habido/no habido),
 * dirección fiscal completa, CIIU y datos de actividad económica (25+ campos).
 *
 * Flujo interno (resumen):
 * <ol>
 *   <li>{@code uspUsuarioObtenerPorId} — nombre del usuario para auditoría.</li>
 *   <li>Validación local del RUC (11 dígitos, prefijo 10 o 20) — sin consumir el plan.</li>
 *   <li>{@code uspResolverApiExternaPorUsuarioYFuncion(@UsuarioId, 'SUNAT_RUC')} — resuelve
 *       endpoint, token AES-256 y {@code ApiServicesFuncionId} desde {@code IT_ApiAsignacion}.</li>
 *   <li>{@code uspPlanValidarLimiteUsuario} — corta con 429 si el límite mensual está agotado.</li>
 *   <li>HTTP GET a Decolecta con el token descifrado. Timeout configurable (default 60 s).</li>
 *   <li>{@code uspConsumoRegistrar} — registra en {@code IT_Consumo} con {@code EsConsulta=1},
 *       siempre, tanto en éxito como en fallo (R2).</li>
 * </ol>
 *
 * Extensibilidad: si en el futuro se integra un segundo proveedor, basta con crear
 * una nueva clase que implemente esta interfaz y anotarla con {@code @Primary}.
 * {@link SunatController} y cualquier componente que dependa de {@code ISunatService}
 * no requieren ningún cambio.
 */
public interface ISunatService {

    /**
     * Consulta los datos de un contribuyente en SUNAT por su número de RUC.
     *
     * <p>El RUC debe tener exactamente 11 dígitos y comenzar con {@code 10} (persona natural)
     * o {@code 20} (persona jurídica). Si el RUC tiene formato válido pero SUNAT no tiene
     * datos para él, Decolecta retorna campos vacíos; el consumo se registra igualmente (R2).</p>
     *
     * @param usuarioId  ID del usuario autenticado, extraído del {@code SecurityContext}
     *                   por {@link SunatController} tras validar JWT y API Key.
     * @param numeroRuc  Número de RUC de 11 dígitos. La validación de formato se realiza
     *                   antes de llamar al proveedor para evitar cobros por datos inválidos.
     * @return           {@link SunatResponse} con los datos del contribuyente y contexto
     *                   del plan ({@code plan}, {@code consumoActual}, {@code limiteMaximo}).
     */
    SunatResponse consultarRuc(int usuarioId, String numeroRuc);
}
