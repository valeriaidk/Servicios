package pe.extech.utilitarios.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * En QAS (true): el body completo del proveedor se incluye en el campo "detalles"
     * del response, lo que facilita el diagnóstico en Postman.
     *
     * En producción (false o ausente): el body del proveedor solo se loguea en el
     * servidor. El response al cliente no expone información interna del proveedor.
     *
     * Configura en application.properties:
     *   extech.debug.exponer-error-proveedor=true   ← QAS
     *   extech.debug.exponer-error-proveedor=false  ← producción
     */
    @Value("${extech.debug.exponer-error-proveedor:false}")
    private boolean exponerErrorProveedor;

    @ExceptionHandler(LimiteAlcanzadoException.class)
    public ResponseEntity<ErrorResponse> handleLimite(LimiteAlcanzadoException ex) {
        // Agregar opciones de upgrade basadas en el plan actual del usuario.
        // El mensaje base viene del SP uspPlanValidarLimiteUsuario (MensajeError).
        // Aquí se complementa con las alternativas disponibles para que el cliente
        // sepa exactamente a qué plan puede pasar sin tener que preguntar a soporte.
        String planActual = ex.getDetalles().containsKey("plan")
                ? String.valueOf(ex.getDetalles().get("plan")) : "";
        String mensajeFinal = ex.getMessage() + opcionesUpgradePlan(planActual);
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(new ErrorResponse("LIMITE_ALCANZADO", mensajeFinal, ex.getDetalles()));
    }

    /**
     * Devuelve la frase de upgrade que se añade al mensaje de límite alcanzado.
     * Solo informa — nunca ejecuta un cambio de plan automático.
     *
     * FREE     → puede ir a BASIC, PRO o ENTERPRISE
     * BASIC    → puede ir a PRO o ENTERPRISE
     * PRO      → solo ENTERPRISE queda disponible
     * ENTERPRISE / SIN_PLAN / vacío → no aplica upgrade
     */
    private String opcionesUpgradePlan(String plan) {
        return switch (plan.toUpperCase()) {
            case "FREE"  -> " Para continuar puedes cambiar a: BASIC (100/mes), PRO (1.000/mes) o ENTERPRISE (sin límite).";
            case "BASIC" -> " Para continuar puedes cambiar a: PRO (1.000/mes) o ENTERPRISE (sin límite).";
            case "PRO"   -> " Para continuar puedes cambiar a ENTERPRISE (sin límite).";
            default      -> "";
        };
    }

    @ExceptionHandler(ApiKeyInvalidaException.class)
    public ResponseEntity<ErrorResponse> handleApiKey(ApiKeyInvalidaException ex) {
        // No loguear detalle: evitar exponer info de seguridad
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(new ErrorResponse("API_KEY_INVALIDA", "API Key inválida o expirada."));
    }

    @ExceptionHandler(UsuarioInactivoException.class)
    public ResponseEntity<ErrorResponse> handleUsuarioInactivo(UsuarioInactivoException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(new ErrorResponse("USUARIO_INACTIVO", ex.getMessage()));
    }

    @ExceptionHandler(ServicioNoDisponibleException.class)
    public ResponseEntity<ErrorResponse> handleServicioNoDisponible(ServicioNoDisponibleException ex) {
        log.error("Proveedor externo no disponible: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ErrorResponse("SERVICIO_NO_DISPONIBLE", ex.getMessage()));
    }

    /**
     * Diferencia los errores reales del proveedor externo según su código HTTP.
     *
     * 401 / 403  → problema de autenticación / autorización con el proveedor.
     *              El token almacenado es inválido, expiró o no tiene permisos.
     *              Retorna 503 porque es un problema de configuración del servidor,
     *              no del cliente que llamó a nuestra API.
     *
     * 400 / 422  → parámetro rechazado por el proveedor (URL, formato, valor).
     *              Retorna 422 para que el cliente pueda corregir su request.
     *
     * 404        → endpoint del proveedor no encontrado (URL mal configurada en BD).
     *              Retorna 503: es un problema de configuración, no del cliente.
     *
     * 5xx        → el proveedor tiene un error interno.
     *              Retorna 503 SERVICIO_NO_DISPONIBLE.
     */
    @ExceptionHandler(ProveedorExternoException.class)
    public ResponseEntity<ErrorResponse> handleProveedorExterno(ProveedorExternoException ex) {
        int status = ex.getStatusProveedor();

        Map<String, Object> detalles = new LinkedHashMap<>();
        detalles.put("proveedor", ex.getProveedor());
        detalles.put("statusProveedor", status);
        // bodyProveedor solo se expone en el response si el flag de debug está activo (QAS).
        // En producción siempre queda en los logs del servidor (ver log.error más abajo).
        if (exponerErrorProveedor && ex.getBodyProveedor() != null && !ex.getBodyProveedor().isBlank()) {
            detalles.put("bodyProveedor", ex.getBodyProveedor());
        }

        if (status == 401 || status == 403) {
            log.error("[ProveedorExterno] {} respondió {} — token inválido o sin autorización. " +
                      "Verificar IT_ApiExternaFuncion.Token y columna Autorizacion.",
                      ex.getProveedor(), status);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(new ErrorResponse(
                            "ERROR_AUTENTICACION_PROVEEDOR",
                            "El token de acceso al proveedor " + ex.getProveedor() +
                            " es inválido o expiró (HTTP " + status + "). " +
                            "Contacta a soporte para actualizar las credenciales.",
                            detalles));
        }

        if (status == 400 || status == 422) {
            log.error("[ProveedorExterno] {} respondió {} — parámetro rechazado. Body: {}",
                      ex.getProveedor(), status, ex.getBodyProveedor());
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                    .body(new ErrorResponse(
                            "PARAMETRO_PROVEEDOR_INVALIDO",
                            "El proveedor " + ex.getProveedor() +
                            " rechazó la solicitud (HTTP " + status + "). " +
                            "Verifica los datos enviados.",
                            detalles));
        }

        if (status == 404) {
            log.error("[ProveedorExterno] {} respondió 404 — endpoint no encontrado. " +
                      "Verificar IT_ApiExternaFuncion.Endpoint.", ex.getProveedor());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(new ErrorResponse(
                            "ENDPOINT_PROVEEDOR_NO_ENCONTRADO",
                            "El endpoint del proveedor " + ex.getProveedor() +
                            " no existe (HTTP 404). Contacta a soporte.",
                            detalles));
        }

        // 5xx u otro código inesperado
        log.error("[ProveedorExterno] {} respondió {} — error interno del proveedor. Body: {}",
                  ex.getProveedor(), status, ex.getBodyProveedor());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ErrorResponse(
                        "SERVICIO_NO_DISPONIBLE",
                        "El proveedor " + ex.getProveedor() +
                        " no está disponible en este momento (HTTP " + status + "). " +
                        "Intenta nuevamente en unos minutos.",
                        detalles));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        String mensaje = ex.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining(", "));
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(new ErrorResponse("CAMPO_REQUERIDO", mensaje));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException ex) {
        // Incluye: DNI_INVALIDO, RUC_INVALIDO, TELEFONO_INVALIDO, template no encontrado, etc.
        // El mensaje viene de ValidadorUtil o de resolverContenido() en EnvioBaseService.
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(new ErrorResponse("VALIDACION_FALLIDA", ex.getMessage()));
    }

    /**
     * Método HTTP incorrecto — el cliente llamó con GET en vez de POST o viceversa.
     * Ejemplo: GET /servicios/sms/enviar cuando el endpoint es POST.
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMetodoNoPermitido(org.springframework.web.HttpRequestMethodNotSupportedException ex) {
        String metodosPermitidos = ex.getSupportedHttpMethods() != null
                ? ex.getSupportedHttpMethods().toString()
                : "desconocido";
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(new ErrorResponse(
                        "METODO_NO_PERMITIDO",
                        "El método '" + ex.getMethod() + "' no está permitido para este endpoint. " +
                        "Métodos aceptados: " + metodosPermitidos + "."));
    }

    /**
     * Parámetro obligatorio de query string ausente.
     * Ejemplo: GET /servicios/reniec/dni sin ?numero=
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponse> handleParametroAusente(MissingServletRequestParameterException ex) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(new ErrorResponse(
                        "PARAMETRO_REQUERIDO",
                        "El parámetro '" + ex.getParameterName() + "' es obligatorio y no fue enviado."));
    }

    /**
     * Body JSON malformado o Content-Type incorrecto.
     * Ejemplo: POST con un JSON que tiene un error de sintaxis.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleJsonInvalido(HttpMessageNotReadableException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse(
                        "JSON_INVALIDO",
                        "El cuerpo de la solicitud no es un JSON válido. " +
                        "Verifica que el Content-Type sea 'application/json' y que el formato sea correcto."));
    }

    /**
     * Endpoint no encontrado — URL que no existe en el sistema.
     * Ejemplo: /servicios/reniec/buscar (ruta no mapeada).
     *
     * Requiere spring.mvc.throw-exception-if-no-handler-found=true en application.properties.
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleEndpointNoEncontrado(NoResourceFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse(
                        "ENDPOINT_NO_ENCONTRADO",
                        "La ruta '" + ex.getResourcePath() + "' no existe. " +
                        "Verifica la URL y el método HTTP."));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneral(Exception ex) {
        log.error("Error inesperado: {}", ex.getMessage(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse("ERROR_INTERNO", "Error inesperado del servidor. " +
                        "Si el problema persiste, contacta a soporte en soporte@extech.pe."));
    }
}
