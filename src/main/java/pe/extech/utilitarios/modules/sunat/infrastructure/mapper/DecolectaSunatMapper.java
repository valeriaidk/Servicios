package pe.extech.utilitarios.modules.sunat.infrastructure.mapper;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import pe.extech.utilitarios.modules.sunat.dto.SunatResponse;
import pe.extech.utilitarios.util.PlanContext;

import java.util.List;
import java.util.Map;

/**
 * Mapeador de la respuesta cruda de Decolecta (snake_case) al DTO
 * {@link SunatResponse} (camelCase) que expone el API.
 *
 * <p>
 * Extraído del antiguo {@code SunatService} para separar la orquestación
 * (UseCase) del mapeo específico del proveedor.
 * </p>
 */
@Slf4j
@Component
public class DecolectaSunatMapper {

    private static final String SERVICIO_NOMBRE = "Consulta RUC";
    private static final String SERVICIO_CODIGO = "SUNAT_RUC";
    private static final String SERVICIO_DESCRIPCION = "Consulta de datos por RUC";

    private final ObjectMapper objectMapper;

    public DecolectaSunatMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Convierte la respuesta externa en el DTO de dominio enriquecido con
     * el contexto del plan y la identidad del usuario.
     */
    @SuppressWarnings({ "rawtypes", "unchecked" })
    public SunatResponse mapear(Map externa, String ruc, int usuarioId,
            String nombreUsuario, PlanContext plan, int funcionId) {

        if (externa == null) {
            throw new IllegalStateException("Respuesta vacía de Decolecta-SUNAT");
        }

        // Resolver el nodo que contiene los datos (algunas variantes vienen bajo
        // "data", "result" o "contribuyente").
        Map<String, Object> datos = externa;
        for (String nodo : new String[] { "data", "result", "contribuyente" }) {
            Object candidato = externa.get(nodo);
            if (candidato instanceof Map) {
                datos = (Map<String, Object>) candidato;
                break;
            }
        }

        SunatResponse.SunatData data = new SunatResponse.SunatData(
                str(datos, "numero_documento", ruc),
                str(datos, "razon_social", "razonSocial"),
                str(datos, "tipo", "tipo"),
                str(datos, "estado", "estado"),
                str(datos, "condicion", "condicion"),
                str(datos, "direccion", "direccion"),
                str(datos, "ubigeo", "ubigeo"),
                str(datos, "via_tipo", "viaTipo"),
                str(datos, "via_nombre", "viaNombre"),
                str(datos, "zona_codigo", "zonaCodigo"),
                str(datos, "zona_tipo", "zonaTipo"),
                str(datos, "numero", "numero"),
                str(datos, "interior", "interior"),
                str(datos, "lote", "lote"),
                str(datos, "dpto", "dpto"),
                str(datos, "manzana", "manzana"),
                str(datos, "kilometro", "kilometro"),
                str(datos, "distrito", "distrito"),
                str(datos, "provincia", "provincia"),
                str(datos, "departamento", "departamento"),
                parseBool(datos, "es_agente_retencion", "esAgenteRetencion"),
                parseBool(datos, "es_buen_contribuyente", "esBuenContribuyente"),
                str(datos, "actividad_economica", "actividadEconomica"),
                str(datos, "tipo_facturacion", "tipoFacturacion"),
                str(datos, "tipo_contabilidad", "tipoContabilidad"),
                str(datos, "comercio_exterior", "comercioExterior"),
                str(datos, "numero_trabajadores", "numeroTrabajadores"),
                parseList(datos, "locales_anexos", "localesAnexos"));

        return new SunatResponse(
                true,
                "OPERACION_EXITOSA",
                "Consulta realizada correctamente.",
                usuarioId,
                nombreUsuario,
                plan.plan(),
                plan.consumoActual() + 1,
                plan.limiteMaximo(),
                funcionId,
                SERVICIO_NOMBRE,
                SERVICIO_CODIGO,
                SERVICIO_DESCRIPCION,
                data);
    }

    // ------------------------------------------------------------------------
    // Helpers de lectura tolerante a snake_case / camelCase
    // ------------------------------------------------------------------------

    private String str(Map<String, Object> map, String clave1, String clave2) {
        Object v = map.get(clave1);
        if (v == null) {
            v = map.get(clave2);
        }
        return v != null ? v.toString().trim() : "";
    }

    private Boolean parseBool(Map<String, Object> map, String clave1, String clave2) {
        Object v = map.get(clave1);
        if (v == null) {
            v = map.get(clave2);
        }
        if (v == null) {
            return null;
        }
        if (v instanceof Boolean) {
            return (Boolean) v;
        }
        String s = v.toString().trim();
        if (s.equalsIgnoreCase("true")) {
            return Boolean.TRUE;
        }
        if (s.equalsIgnoreCase("false")) {
            return Boolean.FALSE;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private List<Object> parseList(Map<String, Object> map, String clave1, String clave2) {
        Object v = map.get(clave1);
        if (v == null) {
            v = map.get(clave2);
        }
        if (v == null) {
            return null;
        }
        if (v instanceof List) {
            return (List<Object>) v;
        }
        String s = v.toString().trim();
        if (s.startsWith("[")) {
            try {
                return objectMapper.readValue(s, List.class);
            } catch (Exception ignored) {
                log.debug("[SUNAT] No se pudo parsear lista: {}", s);
            }
        }
        return null;
    }
}
