package pe.extech.utilitarios.sunat.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Respuesta enriquecida de consulta SUNAT (SUNAT_RUC).
 *
 * Incluye todos los campos reales que devuelve Decolecta (snake_case → camelCase),
 * más contexto de consumo del plan y datos del servicio para trazabilidad.
 *
 * consumoActual refleja el conteo después de registrar este request.
 * limiteMaximo es null cuando el plan no tiene límite (ej: ENTERPRISE).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SunatResponse(
        boolean ok,
        String codigo,
        String mensaje,
        Integer usuarioId,
        String plan,
        Integer consumoActual,
        Integer limiteMaximo,
        Integer apiServicesFuncionId,
        // Información del servicio consumido (SUNAT_RUC)
        String servicioNombre,
        String servicioCodigo,
        String servicioDescripcion,
        SunatData data
) {
    /**
     * Datos reales del contribuyente devueltos por Decolecta SUNAT.
     * Campos mapeados desde snake_case del proveedor a camelCase.
     */
    public record SunatData(
            // Identificación
            String ruc,               // numero_documento del proveedor
            String razonSocial,       // razon_social
            String tipo,              // tipo (persona natural / jurídica)

            // Estado tributario
            String estado,            // estado (ACTIVO, BAJA, etc.)
            String condicion,         // condicion (HABIDO, NO HABIDO, etc.)

            // Dirección fiscal
            String direccion,         // direccion (concatenada por el proveedor)
            String ubigeo,            // ubigeo
            String viaTipo,           // via_tipo
            String viaNombre,         // via_nombre
            String zonaCodigo,        // zona_codigo
            String zonaTipo,          // zona_tipo
            String numero,            // numero
            String interior,          // interior
            String lote,              // lote
            String dpto,              // dpto
            String manzana,           // manzana
            String kilometro,         // kilometro
            String distrito,          // distrito
            String provincia,         // provincia
            String departamento,      // departamento

            // Clasificación tributaria
            Boolean esAgenteRetencion,      // es_agente_retencion  → boolean real
            Boolean esBuenContribuyente,    // es_buen_contribuyente → boolean real
            String  actividadEconomica,     // actividad_economica
            String  tipoFacturacion,        // tipo_facturacion
            String  tipoContabilidad,       // tipo_contabilidad
            String  comercioExterior,       // comercio_exterior

            // Información laboral/operativa
            String       numeroTrabajadores,  // numero_trabajadores
            List<Object> localesAnexos        // locales_anexos → array JSON real
    ) {}
}
