package pe.extech.utilitarios.modules.sunat;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.reactive.function.client.WebClient;

import pe.extech.utilitarios.exception.LimiteAlcanzadoException;
import pe.extech.utilitarios.exception.ProveedorExternoException;
import pe.extech.utilitarios.exception.ServicioNoDisponibleException;
import pe.extech.utilitarios.modules.sunat.dto.SunatResponse;
import pe.extech.utilitarios.modules.user.domain.repository.ConsumoRepository;
import pe.extech.utilitarios.modules.user.domain.repository.UsuarioRepository;
import pe.extech.utilitarios.util.AesUtil;
import pe.extech.utilitarios.util.PlanContext;
import pe.extech.utilitarios.util.ValidadorUtil;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Servicio SUNAT — consulta de contribuyentes por RUC vía Decolecta.
 *
 * Flujo (R2 — 1 request = 1 consumo en IT_Consumo):
 * 1. Validar RUC localmente (evita cobros por datos inválidos — §14.4)
 * 2. Resolver configuración del proveedor vía SP (ApiServicesFuncionId + token
 * AES)
 * 3. Validar límite de plan (Regla 9: sin plan activo → bloquear)
 * 4. Llamar a Decolecta: GET .../sunat/ruc/full?numero=<ruc> con Bearer
 * <token_real>
 * 5. Registrar en IT_Consumo (R2: siempre, incluso si falla)
 * 6. Retornar respuesta enriquecida con contexto de plan
 *
 * Identidad del usuario: viene del JWT procesado por JwtFilter.
 * Autorización de servicio: X-API-Key validado por ApiKeyFilter (mismo
 * usuario).
 */
@Slf4j
@Service
public class SunatService implements ISunatService {

    private static final String SERVICIO_NOMBRE = "Consulta RUC";
    private static final String SERVICIO_CODIGO = "SUNAT_RUC";
    private static final String SERVICIO_DESCRIPCION = "Consulta de datos por RUC";

    private final SunatRepository sunatRepository;
    private final ConsumoRepository consumoRepository;
    private final UsuarioRepository usuarioRepository;
    private final AesUtil aesUtil;
    private final ObjectMapper objectMapper;
    private final long timeoutMs;

    public SunatService(SunatRepository sunatRepository,
            ConsumoRepository consumoRepository,
            UsuarioRepository usuarioRepository,
            AesUtil aesUtil,
            ObjectMapper objectMapper,
            @Value("${extech.proveedor.decolecta.timeout-ms:60000}") long timeoutMs) {
        this.sunatRepository = sunatRepository;
        this.consumoRepository = consumoRepository;
        this.usuarioRepository = usuarioRepository;
        this.aesUtil = aesUtil;
        this.objectMapper = objectMapper;
        this.timeoutMs = timeoutMs;
    }

    /**
     * Consulta datos de un contribuyente en SUNAT vía Decolecta.
     *
     * @param usuarioId userId del JWT autenticado (establecido por JwtFilter)
     * @param rucParam  RUC de 11 dígitos recibido como query param ?numero=
     */
    public SunatResponse consultarRuc(int usuarioId, String rucParam) {
        // Resolver nombre del usuario primero — estará disponible en TODOS los paths,
        // incluyendo errores, para que IT_Consumo.UsuarioRegistro siempre identifique
        // quién fue.
        String nombreUsuario = usuarioRepository.obtenerNombrePorId(usuarioId);

        // Validar RUC localmente antes de gastar un consumo (R2 + §14.4)
        ValidadorUtil.validarRuc(rucParam);

        // Resolver configuración del proveedor: ApiServicesFuncionId + endpoint + token
        // AES
        Map<String, Object> config = sunatRepository.resolverConfiguracion(usuarioId);
        int funcionId = ((Number) config.get("ApiServicesFuncionId")).intValue();
        String payload = "{\"numero\":\"" + rucParam + "\"}";

        // Validar límite de plan antes de llamar al proveedor
        PlanContext plan = verificarLimite(usuarioId, funcionId, payload, nombreUsuario);

        // Token en IT_ApiExternaFuncion.Token; NUNCA loguear el valor plano.
        // descifrarConFallback() maneja tokens cifrados AES-256 y texto plano legacy.
        String endpoint = (String) config.get("EndpointExterno");
        String autorizacion = (String) config.get("Autorizacion");
        String tokenReal = aesUtil.descifrarConFallback((String) config.get("Token"));

        // ── Validación del template de Autorización ───────────────────────────
        if (autorizacion == null || !autorizacion.contains("{TOKEN}")) {
            log.error("[SUNAT] CONFIGURACIÓN INCORRECTA - IT_ApiExternaFuncion.Autorizacion no contiene " +
                    "el placeholder {{TOKEN}}. Valor actual: '{}'. Corregir en BD: " +
                    "UPDATE IT_ApiExternaFuncion SET Autorizacion='Bearer {{TOKEN}}' " +
                    "WHERE Codigo='DECOLECTA_SUNAT'", autorizacion);
        } else {
            log.debug("[SUNAT] Autorización configurada correctamente: {}",
                    autorizacion.replace("{TOKEN}", "[TOKEN_OCULTO]"));
        }
        String authHeader = autorizacion != null ? autorizacion.replace("{TOKEN}", tokenReal) : "";
        String authScheme = authHeader.contains(" ")
                ? authHeader.substring(0, authHeader.indexOf(' '))
                : authHeader;

        // ── Construcción de la URL final ──────────────────────────────────────
        // El endpoint en BD es: https://api.decolecta.com/v1/sunat/ruc/full?numero=
        // Ya incluye el nombre del parámetro → concatenar solo el valor del RUC.
        // Si por algún motivo en BD viene sin query string → añadir ?numero=<ruc>
        // se construye la URL hacia Decolecta:
        final String urlFinal;
        if (endpoint.contains("?")) {
            urlFinal = endpoint + rucParam;
        } else {
            urlFinal = endpoint + "?numero=" + rucParam;
        }
        log.info("[SUNAT] Llamada externa → {} | authScheme={} | ruc={} | authHeaderLength={}",
                urlFinal, authScheme, rucParam, authHeader.length());

        SunatResponse respuesta;
        boolean exito = false;
        String responseJson;

        try {
            @SuppressWarnings("rawtypes")
            Map externa = WebClient.builder()
                    .baseUrl(urlFinal)
                    .defaultHeader(HttpHeaders.AUTHORIZATION, authHeader)
                    .build()
                    .get()
                    .retrieve()
                    .bodyToMono(Map.class)
                    .timeout(Duration.ofMillis(timeoutMs))
                    .block();

            // nombreUsuario ya fue resuelto al inicio del método — disponible aquí
            respuesta = mapearRespuesta(externa, rucParam, usuarioId, nombreUsuario, plan, funcionId);
            exito = true;
            responseJson = toJson(respuesta);

        } catch (LimiteAlcanzadoException e) {
            throw e;

        } catch (WebClientResponseException e) {
            String decolectaBody = e.getResponseBodyAsString();
            int httpStatus = e.getStatusCode().value();
            log.error("[SUNAT] ERROR DECOLECTA {} para RUC {}. " +
                    "URL: {} | authScheme: {} | authHeaderLength: {} | Body: {}",
                    httpStatus, rucParam, urlFinal, authScheme,
                    authHeader.length(), decolectaBody);
            responseJson = "{\"httpStatus\":" + httpStatus +
                    ",\"decolectaError\":" +
                    (decolectaBody.isBlank() ? "\"\"" : decolectaBody) + "}";
            consumoRepository.registrar(usuarioId, funcionId, payload, responseJson, false, true, nombreUsuario);
            throw new ProveedorExternoException("Decolecta-SUNAT", httpStatus, decolectaBody);

        } catch (Exception e) {
            log.error("[SUNAT] Error inesperado consultando RUC {}: {}", rucParam, e.getMessage());
            responseJson = "{\"error\": \"" + e.getMessage() + "\"}";
            consumoRepository.registrar(usuarioId, funcionId, payload, responseJson, false, true, nombreUsuario);
            throw new ServicioNoDisponibleException("Decolecta-SUNAT");
        }

        // R2: 1 request = 1 consumo registrado en IT_Consumo (con nombre si fue
        // exitoso)
        consumoRepository.registrar(usuarioId, funcionId, payload, responseJson, exito, true, nombreUsuario);
        return respuesta;
    }

    /**
     * Valida límite de plan antes de consumir el proveedor.
     * Regla 6: si falla, igual registra el consumo con Exito=0.
     * Regla 9: sin plan activo → bloquear.
     */
    private PlanContext verificarLimite(int usuarioId, int funcionId,
            String payload, String nombreUsuario) {
        Map<String, Object> resultado = consumoRepository.validarLimitePlan(usuarioId, funcionId);

        String nombrePlan = resultado.containsKey("NombrePlan")
                ? (String) resultado.get("NombrePlan")
                : "";
        if (nombrePlan == null || nombrePlan.isBlank()) {
            consumoRepository.registrar(usuarioId, funcionId, payload,
                    "Usuario sin plan activo.", false, true, nombreUsuario);
            throw new LimiteAlcanzadoException(
                    "No tienes un plan activo. Contáctate con soporte.", 0, 0, "SIN_PLAN");
        }

        int consumoActual = resultado.containsKey("ConsumoActual")
                ? ((Number) resultado.get("ConsumoActual")).intValue()
                : 0;
        Integer limiteMaximo = resultado.containsKey("LimiteMaximo") && resultado.get("LimiteMaximo") != null
                ? ((Number) resultado.get("LimiteMaximo")).intValue()
                : null;

        if (!ValidadorUtil.bit(resultado.get("PuedeContinuar"))) {
            int lim = limiteMaximo != null ? limiteMaximo : 0;
            String msg = resultado.containsKey("MensajeError")
                    ? (String) resultado.get("MensajeError")
                    : "Límite alcanzado.";
            consumoRepository.registrar(usuarioId, funcionId, payload, msg,
                    false, true, nombreUsuario);
            throw new LimiteAlcanzadoException(msg, consumoActual, lim, nombrePlan);
        }

        return new PlanContext(nombrePlan, consumoActual, limiteMaximo);
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    private SunatResponse mapearRespuesta(Map externa, String ruc,
            int usuarioId, String nombreUsuario,
            PlanContext plan, int funcionId) {
        if (externa == null)
            throw new ServicioNoDisponibleException("Decolecta-SUNAT");

        // ── LOG del body real de Decolecta ────────────────────────────────────
        // Permite ver la estructura y los nombres exactos de campo del proveedor.
        log.info("[SUNAT] Body completo de Decolecta para RUC {}: {}", ruc, externa);

        // ── Resolver nodo de datos ────────────────────────────────────────────
        // Decolecta puede devolver los campos en la raíz o dentro de "data"/"result".
        Map<String, Object> datos = externa;
        for (String nodo : new String[] { "data", "result", "contribuyente" }) {
            if (externa.containsKey(nodo) && externa.get(nodo) instanceof Map) {
                datos = (Map<String, Object>) externa.get(nodo);
                log.debug("[SUNAT] Campos encontrados en nodo '{}': {}", nodo, datos.keySet());
                break;
            }
        }

        // ── Mapeo de campos reales de Decolecta SUNAT_RUC ────────────────────
        // Nombres confirmados: snake_case del proveedor → camelCase de la respuesta.
        // str() intenta clave1 (snake_case) y si está vacío intenta clave2 (camelCase
        // fallback).
        String numeroDocumento = str(datos, "numero_documento", ruc); // fallback al RUC consultado
        String razonSocial = str(datos, "razon_social", "razonSocial");
        String tipo = str(datos, "tipo", "tipo");
        String estado = str(datos, "estado", "estado");
        String condicion = str(datos, "condicion", "condicion");
        String direccion = str(datos, "direccion", "direccion");
        String ubigeo = str(datos, "ubigeo", "ubigeo");
        String viaTipo = str(datos, "via_tipo", "viaTipo");
        String viaNombre = str(datos, "via_nombre", "viaNombre");
        String zonaCodigo = str(datos, "zona_codigo", "zonaCodigo");
        String zonaTipo = str(datos, "zona_tipo", "zonaTipo");
        String numero = str(datos, "numero", "numero");
        String interior = str(datos, "interior", "interior");
        String lote = str(datos, "lote", "lote");
        String dpto = str(datos, "dpto", "dpto");
        String manzana = str(datos, "manzana", "manzana");
        String kilometro = str(datos, "kilometro", "kilometro");
        String distrito = str(datos, "distrito", "distrito");
        String provincia = str(datos, "provincia", "provincia");
        String departamento = str(datos, "departamento", "departamento");
        // Booleans: Decolecta los devuelve como true/false JSON o como strings
        // "true"/"false"
        Boolean esAgenteRet = parseBool(datos, "es_agente_retencion", "esAgenteRetencion");
        Boolean esBuenContrib = parseBool(datos, "es_buen_contribuyente", "esBuenContribuyente");
        String actividadEcon = str(datos, "actividad_economica", "actividadEconomica");
        String tipoFacturacion = str(datos, "tipo_facturacion", "tipoFacturacion");
        String tipoContabilidad = str(datos, "tipo_contabilidad", "tipoContabilidad");
        String comercioExterior = str(datos, "comercio_exterior", "comercioExterior");
        String nroTrabajadores = str(datos, "numero_trabajadores", "numeroTrabajadores");
        // localesAnexos: puede venir como List (JSON array) o como String serializada →
        // parsear
        List<Object> localesAnexos = parseList(datos, "locales_anexos", "localesAnexos");

        log.info("[SUNAT] Campos mapeados → ruc='{}' razonSocial='{}' estado='{}' condicion='{}'",
                numeroDocumento, razonSocial, estado, condicion);

        // consumoActual + 1: este request acaba de registrarse
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
                new SunatResponse.SunatData(
                        numeroDocumento,
                        razonSocial,
                        tipo,
                        estado,
                        condicion,
                        direccion,
                        ubigeo,
                        viaTipo,
                        viaNombre,
                        zonaCodigo,
                        zonaTipo,
                        numero,
                        interior,
                        lote,
                        dpto,
                        manzana,
                        kilometro,
                        distrito,
                        provincia,
                        departamento,
                        esAgenteRet,
                        esBuenContrib,
                        actividadEcon,
                        tipoFacturacion,
                        tipoContabilidad,
                        comercioExterior,
                        nroTrabajadores,
                        localesAnexos));
    }

    /**
     * Lee un campo de {@code map} probando primero {@code clave1} (snake_case del
     * proveedor)
     * y luego {@code clave2} (camelCase o valor de fallback).
     * Devuelve cadena vacía si ninguna clave existe o el valor es nulo.
     */
    private String str(Map<String, Object> map, String clave1, String clave2) {
        Object v = map.get(clave1);
        if (v == null)
            v = map.get(clave2);
        return v != null ? v.toString().trim() : "";
    }

    /**
     * Lee un campo booleano intentando snake_case y luego camelCase.
     * Acepta tanto Boolean nativo (ya deserializado por WebClient) como String
     * "true"/"false".
     * Devuelve null si el campo no existe.
     */
    private Boolean parseBool(Map<String, Object> map, String clave1, String clave2) {
        Object v = map.get(clave1);
        if (v == null)
            v = map.get(clave2);
        if (v == null)
            return null;
        if (v instanceof Boolean)
            return (Boolean) v;
        String s = v.toString().trim();
        if (s.equalsIgnoreCase("true"))
            return Boolean.TRUE;
        if (s.equalsIgnoreCase("false"))
            return Boolean.FALSE;
        return null; // valor no reconocible → omitir en la respuesta (NON_NULL)
    }

    /**
     * Lee un campo que debe ser un arreglo JSON.
     * Si WebClient ya lo deserializó como List → se usa directamente.
     * Si llega como String "[...]" → se intenta parsear con ObjectMapper.
     * Devuelve null si el campo no existe o no es parseable como lista.
     */
    @SuppressWarnings("unchecked")
    private List<Object> parseList(Map<String, Object> map, String clave1, String clave2) {
        Object v = map.get(clave1);
        if (v == null)
            v = map.get(clave2);
        if (v == null)
            return null;
        if (v instanceof List)
            return (List<Object>) v;
        String s = v.toString().trim();
        if (s.startsWith("[")) {
            try {
                return objectMapper.readValue(s, List.class);
            } catch (Exception ignored) {
                log.debug("[SUNAT] No se pudo parsear localesAnexos como lista: {}", s);
            }
        }
        return null; // no es lista parseable → omitir en la respuesta (NON_NULL)
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return obj != null ? obj.toString() : null;
        }
    }
}
