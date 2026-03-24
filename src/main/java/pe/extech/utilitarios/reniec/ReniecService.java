package pe.extech.utilitarios.reniec;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.reactive.function.client.WebClient;
import pe.extech.utilitarios.domain.consumo.ConsumoRepository;
import pe.extech.utilitarios.domain.usuario.UsuarioRepository;
import pe.extech.utilitarios.exception.LimiteAlcanzadoException;
import pe.extech.utilitarios.exception.ProveedorExternoException;
import pe.extech.utilitarios.exception.ServicioNoDisponibleException;
import pe.extech.utilitarios.reniec.dto.ReniecResponse;
import pe.extech.utilitarios.util.AesUtil;
import pe.extech.utilitarios.util.PlanContext;
import pe.extech.utilitarios.util.ValidadorUtil;

import java.time.Duration;
import java.util.Map;

/**
 * Servicio RENIEC — consulta de personas por DNI vía Decolecta.
 *
 * Flujo (R2 — 1 request = 1 consumo en IT_Consumo):
 * 1. Validar DNI localmente (evita cobros por datos inválidos al proveedor — §14.4)
 * 2. Resolver configuración del proveedor vía SP (ApiServicesFuncionId + token AES)
 * 3. Validar límite de plan (R9: sin plan activo → bloquear)
 * 4. Llamar a Decolecta: GET .../reniec/dni?numero=<dni>  con Bearer <token_real>
 * 5. Registrar en IT_Consumo (R2: siempre, incluso si falla)
 * 6. Retornar respuesta enriquecida con contexto de plan
 *
 * Identidad del usuario: viene del JWT procesado por JwtFilter.
 * Autorización de servicio: X-API-Key validado por ApiKeyFilter (mismo usuario).
 */
@Slf4j
@Service
public class ReniecService implements IReniecService {

    private static final String SERVICIO_NOMBRE      = "Consulta DNI";
    private static final String SERVICIO_CODIGO      = "RENIEC_DNI";
    private static final String SERVICIO_DESCRIPCION = "Consulta de datos por DNI";

    private final ReniecRepository reniecRepository;
    private final ConsumoRepository consumoRepository;
    private final UsuarioRepository usuarioRepository;
    private final AesUtil aesUtil;
    private final ObjectMapper objectMapper;
    private final long timeoutMs;

    public ReniecService(ReniecRepository reniecRepository,
                         ConsumoRepository consumoRepository,
                         UsuarioRepository usuarioRepository,
                         AesUtil aesUtil,
                         ObjectMapper objectMapper,
                         @Value("${extech.proveedor.decolecta.timeout-ms:60000}") long timeoutMs) {
        this.reniecRepository  = reniecRepository;
        this.consumoRepository = consumoRepository;
        this.usuarioRepository = usuarioRepository;
        this.aesUtil           = aesUtil;
        this.objectMapper      = objectMapper;
        this.timeoutMs         = timeoutMs;
    }

    /**
     * Consulta datos de una persona en RENIEC vía Decolecta.
     *
     * @param usuarioId  userId del JWT autenticado (establecido por JwtFilter)
     * @param dniParam   DNI de 8 dígitos recibido como query param ?numero=
     */
    public ReniecResponse consultarDni(int usuarioId, String dniParam) {
        // Resolver nombre del usuario primero — estará disponible en TODOS los paths,
        // incluyendo errores, para que IT_Consumo.UsuarioRegistro siempre identifique quién fue.
        String nombreUsuario = usuarioRepository.obtenerNombrePorId(usuarioId);

        // Validar DNI localmente antes de gastar un consumo (R2 + §14.4)
        // ValidadorUtil lanza IllegalArgumentException si el formato es incorrecto.
        ValidadorUtil.validarDni(dniParam);

        // Resolver configuración del proveedor: ApiServicesFuncionId + endpoint + token AES
        Map<String, Object> config = reniecRepository.resolverConfiguracion(usuarioId);
        int funcionId = ((Number) config.get("ApiServicesFuncionId")).intValue();
        String payload = "{\"numero\":\"" + dniParam + "\"}";

        // Validar límite de plan antes de llamar al proveedor
        PlanContext plan = verificarLimite(usuarioId, funcionId, payload, nombreUsuario);

        String dni = dniParam;

        // Token en IT_ApiExternaFuncion.Token; NUNCA loguear el valor plano.
        // descifrarConFallback() maneja tanto tokens cifrados AES-256 (producción)
        // como tokens almacenados como texto plano (compatibilidad con datos existentes).
        String endpoint    = (String) config.get("EndpointExterno");
        String autorizacion = (String) config.get("Autorizacion");
        String tokenReal   = aesUtil.descifrarConFallback((String) config.get("Token"));

        // ── Validación del template de Autorización ───────────────────────────
        // Si la columna Autorizacion no contiene el placeholder {TOKEN}, el replace
        // no hace nada y el header se envía sin token (causa directa de 401).
        if (autorizacion == null || !autorizacion.contains("{TOKEN}")) {
            log.error("[RENIEC] CONFIGURACIÓN INCORRECTA - IT_ApiExternaFuncion.Autorizacion no contiene el placeholder " +
                     "{{TOKEN}}. Valor actual: '{}'. El header Authorization se enviará sin token " +
                     "→ Decolecta responderá 401/403. Corregir en BD: UPDATE IT_ApiExternaFuncion " +
                     "SET Autorizacion='Bearer {TOKEN}' WHERE Codigo='DECOLECTA_RENIEC'",
                     autorizacion);
        } else {
            log.debug("[RENIEC] Autorización configurada correctamente: {}", autorizacion.replace("{TOKEN}", "[TOKEN_OCULTO]"));
        }
        String authHeader  = autorizacion != null ? autorizacion.replace("{TOKEN}", tokenReal) : "";
        String authScheme  = authHeader.contains(" ")
                             ? authHeader.substring(0, authHeader.indexOf(' '))
                             : authHeader;

        // ── Construcción de la URL final ──────────────────────────────────────
        // El endpoint almacenado en IT_ApiExternaFuncion.Endpoint puede venir en dos formas:
        //   a) URL base sin query string:  "https://api.decolecta.com/v1/reniec/dni"
        //      → el código añade el parámetro: ?numero=<dni>
        //   b) URL con query string parcial: "https://api.decolecta.com/v1/reniec/dni?numero="
        //      → el código solo concatena el valor del DNI al final
        // Ambas formas quedan como: https://api.decolecta.com/v1/reniec/dni?numero=72537503
        final String urlFinal;
        if (endpoint.contains("?")) {
            // El endpoint ya incluye el nombre del parámetro — solo concatenar el valor
            urlFinal = endpoint + dni;
        } else {
            // Endpoint sin query string — usar el nombre de parámetro estándar de Decolecta
            urlFinal = endpoint + "?numero=" + dni;
        }
        log.info("[RENIEC] Llamada externa → {} | authScheme={} | dni={} | authHeaderLength={}", 
                 urlFinal, authScheme, dni, authHeader.length());

        ReniecResponse respuesta;
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
            respuesta = mapearRespuesta(externa, dni, usuarioId, nombreUsuario, plan, funcionId);
            exito = true;
            responseJson = toJson(respuesta);

        } catch (LimiteAlcanzadoException e) {
            throw e;

        } catch (WebClientResponseException e) {
            // Captura separada para exponer el body de error que Decolecta devuelve.
            // Ese body contiene el detalle real de por qué se rechazó la autenticación
            // (ej: "Invalid token", "Token expired", "Unauthorized", etc.).
            String decolectaBody = e.getResponseBodyAsString();
            int httpStatus = e.getStatusCode().value();
            
            // Análisis específico del error para dar pistas claras
            String analisisError = "";
            if (decolectaBody.contains("Apikey Required") || decolectaBody.contains("Limit Exceeded")) {
                analisisError = "PROBABLE CAUSA: Token sin saldo, vencido o límite agotado en Decolecta";
            } else if (decolectaBody.contains("Unauthorized") || httpStatus == 401) {
                analisisError = "PROBABLE CAUSA: Token inválido o mal configurado en BD";
            } else if (httpStatus == 403) {
                analisisError = "PROBABLE CAUSA: Token no tiene permisos para este servicio";
            }
            
            log.error("[RENIEC] ERROR DECOLECTA {} para DNI {}. " +
                      "URL: {} | authScheme: {} | authHeaderLength: {} | Body: {} | {}",
                      httpStatus, dni, urlFinal, authScheme, authHeader.length(), decolectaBody, analisisError);
            
            responseJson = "{\"httpStatus\":" + httpStatus +
                           ",\"decolectaError\":" + (decolectaBody.isBlank() ? "\"\"" : decolectaBody) +
                           ",\"analisis\":\"" + analisisError + "\"}";
            consumoRepository.registrar(usuarioId, funcionId, payload, responseJson, false, true, nombreUsuario);
            throw new ProveedorExternoException("Decolecta-RENIEC", httpStatus, decolectaBody);

        } catch (Exception e) {
            log.error("[RENIEC] Error inesperado consultando DNI {}: {}", dni, e.getMessage());
            responseJson = "{\"error\": \"" + e.getMessage() + "\"}";
            // R2: registrar consumo fallido
            consumoRepository.registrar(usuarioId, funcionId, payload, responseJson, false, true, nombreUsuario);
            throw new ServicioNoDisponibleException("Decolecta-RENIEC");
        }

        // R2: 1 request = 1 consumo registrado en IT_Consumo (con nombre si fue exitoso)
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

        // Regla 9: NombrePlan vacío = sin plan activo
        String nombrePlan = resultado.containsKey("NombrePlan")
                ? (String) resultado.get("NombrePlan") : "";
        if (nombrePlan == null || nombrePlan.isBlank()) {
            consumoRepository.registrar(usuarioId, funcionId, payload,
                    "Usuario sin plan activo.", false, true, nombreUsuario);
            throw new LimiteAlcanzadoException(
                    "No tienes un plan activo. Contáctate con soporte.", 0, 0, "SIN_PLAN");
        }

        int consumoActual = resultado.containsKey("ConsumoActual")
                ? ((Number) resultado.get("ConsumoActual")).intValue() : 0;
        Integer limiteMaximo = resultado.containsKey("LimiteMaximo") && resultado.get("LimiteMaximo") != null
                ? ((Number) resultado.get("LimiteMaximo")).intValue() : null;

        // BIT: PuedeContinuar viene como Boolean del driver MS JDBC
        if (!ValidadorUtil.bit(resultado.get("PuedeContinuar"))) {
            int lim = limiteMaximo != null ? limiteMaximo : 0;
            String msg = resultado.containsKey("MensajeError")
                    ? (String) resultado.get("MensajeError") : "Límite alcanzado.";
            consumoRepository.registrar(usuarioId, funcionId, payload, msg,
                    false, true, nombreUsuario);
            throw new LimiteAlcanzadoException(msg, consumoActual, lim, nombrePlan);
        }

        return new PlanContext(nombrePlan, consumoActual, limiteMaximo);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private ReniecResponse mapearRespuesta(Map externa, String dni,
                                           int usuarioId, String nombreUsuario,
                                           PlanContext plan, int funcionId) {
        if (externa == null) throw new ServicioNoDisponibleException("Decolecta-RENIEC");

        // ── LOG del body real de Decolecta ────────────────────────────────────
        // Permite ver la estructura y los nombres de campo reales que devuelve
        // el proveedor. Útil para diagnosticar campos vacíos.
        log.info("[RENIEC] Body completo de Decolecta para DNI {}: {}", dni, externa);

        // ── Resolver el nodo de datos ─────────────────────────────────────────
        // Decolecta puede devolver los campos directamente en la raíz
        // o anidados dentro de un objeto "data", "result" o "persona".
        Map<String, Object> datos = externa;
        for (String nodo : new String[]{"data", "result", "persona"}) {
            if (externa.containsKey(nodo) && externa.get(nodo) instanceof Map) {
                datos = (Map<String, Object>) externa.get(nodo);
                log.debug("[RENIEC] Campos encontrados en nodo '{}': {}", nodo, datos.keySet());
                break;
            }
        }

        // ── Mapeo de campos reales de Decolecta RENIEC_DNI ───────────────────
        // Nombres de campo confirmados del proveedor:
        //   first_name        → nombres
        //   first_last_name   → apellidoPaterno
        //   second_last_name  → apellidoMaterno
        //   full_name         → nombreCompleto
        String nombres        = str(datos, "first_name",       "nombres");
        String apellidoPat    = str(datos, "first_last_name",  "apellidoPaterno");
        String apellidoMat    = str(datos, "second_last_name", "apellidoMaterno");

        // full_name viene ya armado del proveedor; si por algún motivo llega vacío
        // se construye localmente para garantizar que nunca quede en blanco.
        String nombreCompleto = str(datos, "full_name", "nombreCompleto");
        if (nombreCompleto.isBlank()) {
            nombreCompleto = (nombres + " " + apellidoPat + " " + apellidoMat).trim();
        }

        log.info("[RENIEC] Campos mapeados → nombres='{}' aPat='{}' aMat='{}' completo='{}'",
                 nombres, apellidoPat, apellidoMat, nombreCompleto);

        // consumoActual + 1: este request acaba de registrarse
        return new ReniecResponse(
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
                new ReniecResponse.ReniecData(dni, nombres, apellidoPat,
                        apellidoMat, nombreCompleto)
        );
    }

    /**
     * Lee un campo de {@code map} probando primero {@code clave1} y luego {@code clave2}.
     * Devuelve cadena vacía si ninguna clave existe o el valor es nulo.
     */
    private String str(Map<String, Object> map, String clave1, String clave2) {
        Object v = map.get(clave1);
        if (v == null) v = map.get(clave2);
        return v != null ? v.toString().trim() : "";
    }

    private String toJson(Object obj) {
        try { return objectMapper.writeValueAsString(obj); }
        catch (Exception e) { return obj != null ? obj.toString() : null; }
    }
}
