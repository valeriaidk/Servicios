package pe.extech.utilitarios.reniec;

import pe.extech.utilitarios.reniec.dto.ReniecResponse;

/**
 * Contrato del servicio de consulta RENIEC.
 *
 * Implementación actual: ReniecService (proveedor Decolecta).
 * Si en el futuro se integra un segundo proveedor, basta con agregar
 * una nueva clase que implemente esta interfaz y marcarla con @Primary.
 * El controller y cualquier componente que dependa de IReniecService
 * no requiere ningún cambio.
 */
public interface IReniecService {

    /**
     * Consulta los datos de una persona por su DNI.
     *
     * @param usuarioId  ID del usuario autenticado (extraído del SecurityContext).
     * @param numeroDni  DNI de 8 dígitos a consultar.
     * @return           Respuesta enriquecida con datos de la persona y contexto del plan.
     */
    ReniecResponse consultarDni(int usuarioId, String numeroDni);
}
