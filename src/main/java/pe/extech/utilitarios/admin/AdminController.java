package pe.extech.utilitarios.admin;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.simple.SimpleJdbcCall;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import pe.extech.utilitarios.domain.consumo.ConsumoRepository;
import pe.extech.utilitarios.domain.plan.PlanRepository;
import pe.extech.utilitarios.domain.usuario.UsuarioRepository;
import pe.extech.utilitarios.util.AesUtil;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Tag(name = "Admin", description = "Gestión de usuarios y planes. Solo administradores.")
@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
@Validated
public class AdminController {

    private final UsuarioRepository usuarioRepository;
    private final PlanRepository planRepository;
    private final ConsumoRepository consumoRepository;
    private final AesUtil aesUtil;
    private final JdbcTemplate jdbcTemplate;

    @Operation(summary = "Listar todos los planes activos",
               description = "Requiere JWT de un email en `extech.admin.emails`.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Lista de planes"),
        @ApiResponse(responseCode = "401", description = "JWT inválido"),
        @ApiResponse(responseCode = "403", description = "El usuario no es administrador")
    })
    @GetMapping("/planes")
    public ResponseEntity<Object> listarPlanes() {
        return ResponseEntity.ok(Map.of("ok", true, "planes", planRepository.listarPlanes()));
    }

    @Operation(summary = "Activar usuario por ID",
               description = "Llama a `uspUsuarioActivarDesactivar(@Activo=1)`. Registra el ID del admin como `UsuarioModificacion`.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Usuario activado (o no encontrado)"),
        @ApiResponse(responseCode = "401", description = "JWT inválido"),
        @ApiResponse(responseCode = "403", description = "El usuario no es administrador")
    })
    @PutMapping("/usuarios/{id}/activar")
    public ResponseEntity<Object> activarUsuario(@PathVariable int id, Authentication auth) {
        int adminId = (int) auth.getPrincipal();
        int filas = usuarioRepository.activar(id, adminId);
        return ResponseEntity.ok(Map.of("ok", filas > 0, "mensaje",
                filas > 0 ? "Usuario activado." : "Usuario no encontrado."));
    }

    @Operation(summary = "Desactivar usuario por ID",
               description = "Llama a `uspUsuarioActivarDesactivar(@Activo=0)`. El usuario no podrá consumir servicios mientras esté inactivo.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Usuario desactivado (o no encontrado)"),
        @ApiResponse(responseCode = "401", description = "JWT inválido"),
        @ApiResponse(responseCode = "403", description = "El usuario no es administrador")
    })
    @PutMapping("/usuarios/{id}/desactivar")
    public ResponseEntity<Object> desactivarUsuario(@PathVariable int id, Authentication auth) {
        int adminId = (int) auth.getPrincipal();
        int filas = usuarioRepository.desactivar(id, adminId);
        return ResponseEntity.ok(Map.of("ok", filas > 0, "mensaje",
                filas > 0 ? "Usuario desactivado." : "Usuario no encontrado."));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // HERRAMIENTA QAS — ELIMINAR ANTES DE PRODUCCIÓN
    // Genera el valor AES-256-CBC+Base64 listo para insertar en
    // IT_ApiExternaFuncion.Token.  Solo accesible con JWT de administrador.
    // ─────────────────────────────────────────────────────────────────────────
    @Operation(
        summary = "[QAS] Cifrar token con AES-256 — ELIMINAR en producción",
        description = """
            **Solo QAS.** Cifra un valor en texto plano con la clave AES del sistema
            (extech.aes.clave). Úsalo para obtener el valor cifrado que debe guardarse
            en IT_ApiExternaFuncion.Token.

            Pasos:
            1. Llamar con el token real del proveedor en `value`.
            2. Copiar el campo `cifrado` de la respuesta.
            3. Ejecutar en SSMS:
               `UPDATE dbo.IT_ApiExternaFuncion SET Token = '<cifrado>' WHERE Codigo = 'DECOLECTA_RENIEC'`
            4. **Eliminar este endpoint antes de pasar a producción.**
            """
    )
    @PostMapping("/tools/cifrar")
    public ResponseEntity<Object> cifrarToken(@RequestBody Map<String, String> body) {
        String valorPlano = body.get("value");
        if (valorPlano == null || valorPlano.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("ok", false, "mensaje", "El campo 'value' es obligatorio."));
        }
        String cifrado = aesUtil.cifrar(valorPlano);
        // El valor plano NO se loguea ni se incluye en la respuesta — solo el cifrado.
        return ResponseEntity.ok(Map.of("ok", true, "cifrado", cifrado));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // HERRAMIENTA QAS — DIAGNÓSTICO DE PROVEEDORES — ELIMINAR ANTES DE PRODUCCIÓN
    //
    // Lee IT_ApiExternaFuncion y muestra exactamente cómo se armaría el request
    // a cada proveedor (URL final + esquema de Authorization) SIN hacer la llamada.
    // Útil para verificar:
    //   - que Autorizacion contiene el placeholder {TOKEN}
    //   - que el endpoint tiene el formato correcto (con o sin query string)
    //   - que el esquema de auth es el esperado (Bearer, App, etc.)
    // El token real NO se incluye en la respuesta.
    // ─────────────────────────────────────────────────────────────────────────
    @Operation(
        summary = "[QAS] Diagnosticar configuración de proveedores externos — ELIMINAR en producción",
        description = """
            **Solo QAS.** Lee IT_ApiExternaFuncion para todos los proveedores activos y muestra
            exactamente cómo el backend armaría el request externo sin ejecutarlo:
            - URL final (endpoint + formato de query string)
            - Esquema de Authorization (Bearer, App, etc.) — **sin el token**
            - Si el placeholder `{TOKEN}` está presente en Autorizacion
            - Número de caracteres del token almacenado (indicador de presencia, no el valor)

            Usar para verificar que Decolecta/Infobip recibirían el formato correcto.
            Eliminar este endpoint antes de producción.
            """
    )
    @GetMapping("/tools/diagnosticar-proveedores")
    public ResponseEntity<Object> diagnosticarProveedores() {
        // SQL directo intencional: endpoint QAS marcado "ELIMINAR ANTES DE PRODUCCIÓN".
        // Usa LEN(Token) y CASE WHEN Token IS NULL — columnas computadas no disponibles
        // en uspApiExternaObtenerPorCodigo. Crear SP solo para este debug violaría R7.
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            "SELECT Codigo, Endpoint, Metodo, Autorizacion, " +
            "       LEN(Token) AS TokenLongitud, " +
            "       CASE WHEN Token IS NULL THEN 0 ELSE 1 END AS TokenPresente, " +
            "       TiempoConsulta " +
            "FROM dbo.IT_ApiExternaFuncion " +
            "WHERE Activo = 1 AND Eliminado = 0 " +
            "ORDER BY Codigo");

        List<Map<String, Object>> diagnostico = rows.stream().map(row -> {
            Map<String, Object> item = new LinkedHashMap<>();
            String codigo      = (String) row.get("Codigo");
            String endpoint    = (String) row.get("Endpoint");
            String autorizacion = (String) row.get("Autorizacion");
            Integer tokenLen   = row.get("TokenLongitud") != null
                                 ? ((Number) row.get("TokenLongitud")).intValue() : 0;

            item.put("codigo", codigo);
            item.put("metodo", row.get("Metodo"));
            item.put("tiempoConsultaSeg", row.get("TiempoConsulta"));

            // Analizar endpoint
            item.put("endpoint", endpoint);
            item.put("endpointTieneQueryString", endpoint != null && endpoint.contains("?"));
            // Simular cómo se armaría la URL (valor de ejemplo)
            String valorEjemplo = codigo != null && codigo.contains("SUNAT") ? "20100070970" : "72537503";
            String urlSimulada;
            if (endpoint != null && endpoint.contains("?")) {
                urlSimulada = endpoint + valorEjemplo + "  [valor concatenado al final]";
            } else {
                String paramNombre = codigo != null && codigo.contains("SUNAT") ? "ruc" : "numero";
                urlSimulada = endpoint + "?" + paramNombre + "=" + valorEjemplo + "  [parámetro estándar]";
            }
            item.put("urlSimulada", urlSimulada);

            // Analizar autorización
            item.put("autorizacionTemplate", autorizacion);
            item.put("autorizacionTienePlaceholder", autorizacion != null && autorizacion.contains("{TOKEN}"));
            if (autorizacion != null && autorizacion.contains("{TOKEN}")) {
                String scheme = autorizacion.contains(" ")
                                ? autorizacion.substring(0, autorizacion.indexOf(' '))
                                : autorizacion.replace("{TOKEN}", "").trim();
                item.put("authScheme", scheme);
                item.put("authHeaderSimulado", scheme + " <token_de_" + tokenLen + "_chars>");
            } else {
                item.put("authScheme", "PROBLEMA: no contiene {TOKEN}");
                item.put("authHeaderSimulado", autorizacion + "  ← token NO incluido");
            }
            item.put("tokenAlmacenadoChars", tokenLen);
            item.put("tokenPresente", ((Number) row.get("TokenPresente")).intValue() == 1);

            return item;
        }).toList();

        return ResponseEntity.ok(Map.of(
            "ok", true,
            "nota", "Los tokens reales NO se incluyen. Este endpoint es solo para QAS.",
            "proveedores", diagnostico
        ));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GESTIÓN DE APIs EXTERNAS (IT_ApiExternaFuncion)
    //
    // Permite crear o actualizar la configuración de un proveedor externo.
    // El token se recibe en texto plano y el backend lo cifra con AES-256
    // antes de guardarlo en BD (R8 — tokens de proveedores cifrados en BD).
    //
    // El campo "autorizacion" debe enviarse como: "Bearer {TOKEN}" o "App {TOKEN}"
    // Si se envía solo "Bearer" o "App" sin el placeholder, el backend lo corrige
    // automáticamente añadiendo " {TOKEN}" al final.
    // ─────────────────────────────────────────────────────────────────────────
    @Operation(
        summary = "Crear o actualizar configuración de API externa (cifra el token automáticamente)",
        description = """
            Recibe la configuración del proveedor con el **token en texto plano**.
            El backend lo cifra con AES-256-CBC antes de guardarlo en BD.

            Reglas del campo `autorizacion`:
            - Enviar como `"Bearer {TOKEN}"` o `"App {TOKEN}"` (con el placeholder).
            - Si envías solo `"Bearer"` o `"App"`, el sistema añade ` {TOKEN}` automáticamente.

            Si el `codigo` ya existe en BD → **actualiza** el registro.
            Si no existe → **inserta** uno nuevo.

            Ejemplo de request para actualizar RENIEC:
            ```json
            {
              "nombre": "RENIEC",
              "codigo": "DECOLECTA_RENIEC",
              "descripcion": "Consulta DNI completo",
              "endpoint": "https://api.decolecta.com/v1/reniec/dni?numero=",
              "metodo": "GET",
              "token": "sk_2014.E4cobzgiX8cn7zwD2xdDLHYXdzTeCOSh",
              "autorizacion": "Bearer {TOKEN}",
              "tiempoConsulta": 60,
              "segmentoTiempo": "SEG"
            }
            ```
            """
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Registro guardado correctamente"),
        @ApiResponse(responseCode = "400", description = "Campos obligatorios faltantes"),
        @ApiResponse(responseCode = "401", description = "JWT inválido"),
        @ApiResponse(responseCode = "403", description = "El usuario no es administrador"),
        @ApiResponse(responseCode = "500", description = "Error al cifrar o guardar")
    })
    @PostMapping("/apis-externas/guardar")
    public ResponseEntity<Object> guardarApiExterna(
            @Valid @RequestBody ApiExternaGuardarRequest req,
            Authentication auth) {

        int adminId = (int) auth.getPrincipal();

        // ── 1. Completar placeholder en autorizacion si falta ─────────────────
        // "Bearer"       → "Bearer {TOKEN}"
        // "App"          → "App {TOKEN}"
        // "Bearer {TOKEN}" → sin cambio
        String autorizacion = req.autorizacion() != null ? req.autorizacion().trim() : "Bearer {TOKEN}";
        if (!autorizacion.contains("{TOKEN}")) {
            autorizacion = autorizacion + " {TOKEN}";
        }

        // ── 2. Cifrar el token con AES-256-CBC (R8) ───────────────────────────
        // El token llega en texto plano. Se cifra aquí y se guarda el resultado
        // en BD. descifrarConFallback() lo recuperará en tiempo de ejecución.
        String tokenCifrado;
        try {
            tokenCifrado = aesUtil.cifrar(req.token());
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("ok", false, "mensaje",
                            "Error al cifrar el token. Verifica la configuración AES del servidor."));
        }

        // ── 3. UPSERT vía SP ──────────────────────────────────────────────────
        try {
            SimpleJdbcCall call = new SimpleJdbcCall(jdbcTemplate)
                    .withProcedureName("uspApiExternaFuncionGuardar");

            MapSqlParameterSource params = new MapSqlParameterSource()
                    .addValue("Nombre",          req.nombre())
                    .addValue("Codigo",          req.codigo())
                    .addValue("Descripcion",     req.descripcion())
                    .addValue("Endpoint",        req.endpoint())
                    .addValue("Metodo",          req.metodo() != null ? req.metodo().toUpperCase() : "GET")
                    .addValue("Token",           tokenCifrado)
                    .addValue("Autorizacion",    autorizacion)
                    .addValue("Request",         req.request())
                    .addValue("Response",        req.response())
                    .addValue("TiempoConsulta",  req.tiempoConsulta() != null ? req.tiempoConsulta() : 60)
                    .addValue("SegmentoTiempo",  req.segmentoTiempo() != null ? req.segmentoTiempo() : "SEG")
                    .addValue("UsuarioRegistro", adminId);

            Map<String, Object> resultado = call.execute(params);

            return ResponseEntity.ok(Map.of(
                "ok",              true,
                "mensaje",         "Configuración guardada. Token cifrado con AES-256.",
                "codigo",          req.codigo(),
                "autorizacion",    autorizacion,
                "tokenCifradoLen", tokenCifrado.length(),
                "operacion",       resultado.getOrDefault("Operacion", "OK")
            ));

        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("ok", false, "mensaje",
                            "Error al guardar en BD: " + e.getMessage()));
        }
    }

    /**
     * DTO de entrada para guardar/actualizar un proveedor externo.
     * El token se recibe en texto plano; el endpoint lo cifra antes de persistir.
     */
    public record ApiExternaGuardarRequest(
        @NotBlank(message = "nombre es obligatorio")
        String nombre,

        @NotBlank(message = "codigo es obligatorio (ej: DECOLECTA_RENIEC)")
        String codigo,

        String descripcion,

        @NotBlank(message = "endpoint es obligatorio")
        String endpoint,

        @Pattern(regexp = "GET|POST|PUT|PATCH|DELETE",
                 flags = Pattern.Flag.CASE_INSENSITIVE,
                 message = "metodo debe ser GET, POST, PUT, PATCH o DELETE")
        String metodo,

        @NotBlank(message = "token es obligatorio (texto plano — el backend lo cifra)")
        String token,

        String autorizacion,
        String request,
        String response,
        Integer tiempoConsulta,
        String segmentoTiempo
    ) {}

    // ─────────────────────────────────────────────────────────────────────────
    // ACTUALIZAR API EXTERNA (IT_ApiExternaFuncion)
    //
    // A diferencia de /guardar (UPSERT por Código), este endpoint actualiza
    // por ID y tiene 3 casos:
    //   Caso 1 — Actualizar campos + token nuevo  → cifra y sobreescribe Token en BD.
    //   Caso 2 — Actualizar solo campos sin token → NO sobreescribe Token en BD
    //              (el SP usa ISNULL(@Token, Token), preserva el valor cifrado actual).
    //   Caso 3 — Actualizar solo el token         → solo cifra y actualiza Token en BD.
    //
    // El token AES-256-CBC ya cifrado en BD NUNCA se sobreescribe con NULL.
    // ─────────────────────────────────────────────────────────────────────────
    @Operation(
        summary = "Actualizar configuración de API externa por ID",
        description = """
            Actualiza un proveedor externo identificado por `apiExternaFuncionId`.
            Solo se actualizan los campos enviados; los campos `null` conservan su valor actual.

            **Casos de uso del campo `token`:**
            - **Con token** → el backend cifra con AES-256 y actualiza `IT_ApiExternaFuncion.Token`.
            - **Sin token (null u omitido)** → el token cifrado en BD **no se toca**.

            Ejemplo completo (actualizar RENIEC con nuevo token):
            ```json
            {
              "apiExternaFuncionId": 3,
              "nombre": "RENIEC",
              "codigo": "DECOLECTA_RENIEC",
              "endpoint": "https://api.decolecta.com/v1/reniec/dni?numero=",
              "metodo": "GET",
              "token": "sk_2014.E4cobzgiX8cn7zwD2xdDLHYXdzTeCOSh",
              "autorizacion": "Bearer {TOKEN}",
              "tiempoConsulta": 60,
              "segmentoTiempo": "SEG"
            }
            ```

            Ejemplo sin cambiar el token (solo actualizar endpoint):
            ```json
            {
              "apiExternaFuncionId": 3,
              "endpoint": "https://api.decolecta.com/v2/reniec/dni?numero="
            }
            ```
            """
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Registro actualizado correctamente"),
        @ApiResponse(responseCode = "400", description = "apiExternaFuncionId es obligatorio o campos inválidos"),
        @ApiResponse(responseCode = "401", description = "JWT inválido"),
        @ApiResponse(responseCode = "403", description = "El usuario no es administrador"),
        @ApiResponse(responseCode = "404", description = "No se encontró el proveedor con el ID especificado"),
        @ApiResponse(responseCode = "500", description = "Error al cifrar o actualizar")
    })
    @PutMapping("/apis-externas/actualizar")
    public ResponseEntity<Object> actualizarApiExterna(
            @Valid @RequestBody ApiExternaActualizarRequest req,
            Authentication auth) {

        int adminId = (int) auth.getPrincipal();

        // ── 1. Completar placeholder en autorizacion si falta ─────────────────────
        String autorizacion = req.autorizacion() != null ? req.autorizacion().trim() : "Bearer {TOKEN}";
        if (!autorizacion.contains("{TOKEN}")) {
            autorizacion = autorizacion + " {TOKEN}";
        }

        // ── 2. Resolver token para el SP ──────────────────────────────────────────
        // uspApiExternaFuncionGuardar requiere @Token sin valor por defecto.
        // - Si viene token nuevo  → cifrar con AES-256 y usarlo.
        // - Si NO viene token     → leer el token cifrado actual de BD y reenviarlo
        //                           intacto (el SP lo pisa con el mismo valor).
        String tokenParaSP;
        boolean tokenActualizado;

        if (req.token() != null && !req.token().isBlank()) {
            try {
                tokenParaSP    = aesUtil.cifrar(req.token());
                tokenActualizado = true;
            } catch (Exception e) {
                return ResponseEntity.internalServerError()
                        .body(Map.of("ok", false, "mensaje",
                                "Error al cifrar el token. Verifica la configuración AES del servidor."));
            }
        } else {
            // Sin token nuevo: recuperar el cifrado actual para no pisarlo con NULL.
            // SP: uspApiExternaObtenerPorCodigo(@Codigo, @SoloActivo=0)
            // @SoloActivo=0 → incluye registros inactivos (el token debe recuperarse
            // aunque el proveedor esté desactivado temporalmente).
            try {
                List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                        "EXEC dbo.uspApiExternaObtenerPorCodigo ?, ?",
                        req.codigo(), 0);
                if (rows.isEmpty()) {
                    return ResponseEntity.status(404)
                            .body(Map.of("ok", false, "mensaje",
                                    "No se encontró el proveedor con código: " + req.codigo()));
                }
                tokenParaSP = (String) rows.get(0).get("Token");
                tokenActualizado = false;
            } catch (Exception e) {
                return ResponseEntity.status(404)
                        .body(Map.of("ok", false, "mensaje",
                                "No se encontró el proveedor con código: " + req.codigo()));
            }
        }

        // ── 3. UPSERT vía uspApiExternaFuncionGuardar (mismo SP que /guardar) ─────
        // El SP actualiza si Codigo ya existe, inserta si no.
        // Firma real: @Nombre, @Codigo, @Descripcion, @Endpoint, @Metodo,
        //             @Token, @Autorizacion, @Request, @Response,
        //             @TiempoConsulta, @SegmentoTiempo, @UsuarioRegistro
        // No recibe @ApiExternaFuncionId — se ignora si vino en el request.
        try {
            SimpleJdbcCall call = new SimpleJdbcCall(jdbcTemplate)
                    .withProcedureName("uspApiExternaFuncionGuardar");

            MapSqlParameterSource params = new MapSqlParameterSource()
                    .addValue("Nombre",          req.nombre())
                    .addValue("Codigo",          req.codigo())
                    .addValue("Descripcion",     req.descripcion())
                    .addValue("Endpoint",        req.endpoint())
                    .addValue("Metodo",          req.metodo() != null ? req.metodo().toUpperCase() : "GET")
                    .addValue("Token",           tokenParaSP)
                    .addValue("Autorizacion",    autorizacion)
                    .addValue("Request",         req.request())
                    .addValue("Response",        req.response())
                    .addValue("TiempoConsulta",  req.tiempoConsulta() != null ? req.tiempoConsulta() : 60)
                    .addValue("SegmentoTiempo",  req.segmentoTiempo() != null ? req.segmentoTiempo() : "SEG")
                    .addValue("UsuarioRegistro", adminId);

            Map<String, Object> resultado = call.execute(params);

            Map<String, Object> respuesta = new LinkedHashMap<>();
            respuesta.put("ok",              true);
            respuesta.put("mensaje",         tokenActualizado
                    ? "Registro actualizado. Token cifrado con AES-256 y guardado en BD."
                    : "Registro actualizado. Token preservado (no se envió token nuevo).");
            respuesta.put("codigo",           req.codigo());
            respuesta.put("autorizacion",     autorizacion);
            respuesta.put("tokenActualizado", tokenActualizado);
            if (tokenActualizado) respuesta.put("tokenCifradoLen", tokenParaSP.length());
            respuesta.put("operacion",        resultado.getOrDefault("Operacion", "ACTUALIZADO"));

            return ResponseEntity.ok(respuesta);

        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("ok", false, "mensaje",
                            "Error al actualizar en BD: " + e.getMessage()));
        }
    }

    /**
     * DTO de entrada para actualizar un proveedor externo.
     * Identificador: {@code codigo} (clave de UPSERT del SP).
     * {@code nombre}, {@code endpoint} son obligatorios porque el SP los requiere.
     * {@code token} es opcional: si se omite, el token cifrado en BD se preserva.
     */
    public record ApiExternaActualizarRequest(
        @NotBlank(message = "nombre es obligatorio")
        String nombre,

        @NotBlank(message = "codigo es obligatorio (ej: DECOLECTA_RENIEC)")
        String codigo,

        String descripcion,

        @NotBlank(message = "endpoint es obligatorio")
        String endpoint,

        @Pattern(regexp = "GET|POST|PUT|PATCH|DELETE",
                 flags = Pattern.Flag.CASE_INSENSITIVE,
                 message = "metodo debe ser GET, POST, PUT, PATCH o DELETE")
        String metodo,

        // Opcional. Si se envía → se cifra con AES-256 y se guarda en BD.
        // Si es null u omitido → se preserva el token cifrado ya existente.
        String token,

        String autorizacion,
        String request,
        String response,
        Integer tiempoConsulta,
        String segmentoTiempo
    ) {}

    @Operation(summary = "Estadísticas globales de consumo del mes actual",
               description = "Cuenta todos los consumos exitosos del mes en curso, sin filtrar por usuario.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Total de consumos del mes"),
        @ApiResponse(responseCode = "401", description = "JWT inválido"),
        @ApiResponse(responseCode = "403", description = "El usuario no es administrador")
    })
    @GetMapping("/consumo/global")
    public ResponseEntity<Object> consumoGlobal() {
        int total = consumoRepository.obtenerTotalMensual(0, null);
        return ResponseEntity.ok(Map.of("ok", true, "totalMensual", total));
    }
}
