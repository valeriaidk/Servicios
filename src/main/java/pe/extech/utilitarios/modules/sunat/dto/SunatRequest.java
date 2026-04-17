package pe.extech.utilitarios.modules.sunat.dto;

/**
 * DTO de referencia para SUNAT_RUC.
 *
 * El endpoint GET /api/v1/servicios/sunat/ruc?numero=<ruc>
 * recibe el RUC directamente como @RequestParam, no como @RequestBody.
 * Este record se mantiene como referencia de contrato pero no se usa en el
 * controller.
 */
public record SunatRequest(String numero) {
}
