package pe.extech.utilitarios.reniec.dto;

/**
 * Contrato de entrada para consulta de persona por DNI.
 *
 * El endpoint es:
 *   GET /api/v1/servicios/reniec/dni?numero=<dni>
 *
 * Headers requeridos:
 *   Authorization: Bearer <jwt_usuario>
 *   X-API-Key: <api_key_usuario>
 *
 * El DNI se recibe como query param "numero" en el controller (@RequestParam).
 * Este record queda como referencia del modelo de entrada y puede usarse
 * en pruebas o en futuras extensiones del contrato.
 */
public record ReniecRequest(String numero) {}
