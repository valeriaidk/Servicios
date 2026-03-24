package pe.extech.utilitarios.sunat;

import pe.extech.utilitarios.sunat.dto.SunatResponse;

/**
 * Contrato del servicio de consulta SUNAT.
 *
 * Implementación actual: SunatService (proveedor Decolecta).
 * Un segundo proveedor solo requiere una nueva implementación de esta interfaz.
 * El controller no cambia.
 */
public interface ISunatService {

    /**
     * Consulta los datos de un contribuyente por su RUC.
     *
     * @param usuarioId  ID del usuario autenticado (extraído del SecurityContext).
     * @param numeroRuc  RUC de 11 dígitos a consultar (debe comenzar con 10 o 20).
     * @return           Respuesta enriquecida con datos del contribuyente y contexto del plan.
     */
    SunatResponse consultarRuc(int usuarioId, String numeroRuc);
}
