package pe.extech.utilitarios.correo;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import pe.extech.utilitarios.correo.dto.CorreoRequest;
import pe.extech.utilitarios.correo.dto.CorreoResponse;

/**
 * Controller REST para el servicio de envío de correo electrónico.
 *
 * <p>Expone un único endpoint POST {@code /api/v1/servicios/correo/enviar} que recibe el
 * {@link pe.extech.utilitarios.correo.dto.CorreoRequest} validado con {@code @Valid}, extrae
 * el {@code usuarioId} del {@code SecurityContext} (establecido previamente por
 * {@link pe.extech.utilitarios.security.JwtFilter}) y delega la lógica completa
 * a {@link ICorreoService}.</p>
 *
 * <p>Este controller no contiene lógica de negocio: solo coordina la recepción del
 * request y la entrega del response. Toda validación del correo destinatario, resolución
 * de template, flujo OAuth2 con Microsoft Graph y registro de consumo ocurren
 * en {@link CorreoService}.</p>
 *
 * <p>Autenticación: requiere {@code Authorization: Bearer <jwt>} y {@code X-API-Key: <api_key>}
 * en cada request. Ambos headers son validados por los filtros de seguridad antes de que
 * el request llegue aquí (R1).</p>
 */
@Tag(name = "Servicios - Correo", description = "Envío de correo electrónico. Requiere API Key.")
@SecurityRequirement(name = "apiKeyAuth")
@RestController
@RequestMapping("/api/v1/servicios/correo")
@RequiredArgsConstructor
public class CorreoController {

    private final ICorreoService correoService;

    @Operation(
        summary = "Enviar correo electrónico",
        description = """
            Envía un correo electrónico vía Microsoft Graph (OAuth2 client_credentials). Soporta dos modos: INLINE (asunto y HTML libre) y TEMPLATE (plantilla almacenada en `IT_Template` con sustitución de variables `{{clave}}`). Admite múltiples destinatarios en el campo `to`.

            **Autenticación requerida (ambos headers obligatorios):**
            - `Authorization: Bearer <jwt>` — identifica al usuario y su plan activo.
            - `X-API-Key: <api_key>` — autoriza el consumo del servicio para ese usuario.
            Ambas credenciales deben corresponder al mismo usuario registrado.

            **Modos de envío:**
            - `INLINE`: incluir `"mode": "INLINE"`, `"subject": "Asunto"` y `"body_html": "<p>HTML</p>"`.
            - `TEMPLATE`: incluir `"mode": "TEMPLATE"`, el objeto `"template"` con `"channel": "EMAIL"` y `"code": "<código>"`, y el mapa `"variables"` con los valores a sustituir (ej: `{"code": "483921", "minutes": 10, "brand_app_name": "Extech"}`).

            **Stored Procedures ejecutados internamente (en orden):**
            1. `uspUsuarioObtenerPorId(@UsuarioId)` — obtiene el nombre del usuario para registrarlo en la auditoría de `IT_Consumo`.
            2. `uspApiServicesFuncionObtenerPorCodigo('CORREO_ENVIO')` — resuelve el `ApiServicesFuncionId` desde `IT_ApiServicesFuncion`. Necesario para validar el plan y registrar el consumo.
            3. `uspPlanValidarLimiteUsuario(@UsuarioId, @ApiServicesFuncionId)` — verifica si el usuario tiene consumos disponibles en su plan activo. Si ya alcanzó el límite mensual → registra el intento con `Exito=0` y retorna 429.
            4. Validación local del correo del primer destinatario — si falla, se rechaza sin llamar al proveedor ni gastar consumo.
            5. Modo TEMPLATE: `uspTemplateObtenerAsunto(@ApiServicesFuncionId, @Codigo, @Version)` — obtiene cuerpo HTML y asunto desde `IT_Template` (Canal=EMAIL) con sustitución de variables. Si `@Version` es nulo, se usa la versión más reciente.
               Modo INLINE: se usan directamente `body_html` y `subject` del request.
            6. `uspApiExternaObtenerPorCodigo('MICROSOFT_GRAPH_CORREO', @SoloActivo=1)` — obtiene el `clientSecret` cifrado AES-256 desde `IT_ApiExternaFuncion`. Se descifra en tiempo de ejecución con AesUtil y **nunca se loguea**.
            7. POST `login.microsoftonline.com/{tenantId}/oauth2/v2.0/token` (client_credentials) → `access_token`. El token vive solo en memoria durante el request, no se persiste ni loguea.
            8. POST `graph.microsoft.com/v1.0/users/{outlookUser}/sendMail` con `Authorization: Bearer <access_token>`. Timeout: configurable (default 30 s). Graph retorna 202 Accepted sin cuerpo; la referencia en la respuesta es `GRAPH_<timestamp>`.
            9. `uspConsumoRegistrar(...)` — registra el resultado en `IT_Consumo` con `EsConsulta=0`. Se ejecuta **siempre**, tanto si el envío fue exitoso (`Exito=1`) como si falló (`Exito=0`). Solo los exitosos descuentan del límite mensual.

            **Regla R2:** 1 request = 1 registro en `IT_Consumo`, sin excepción.
            **Regla R4:** Correo y SMS comparten `IT_Template` e `IT_Consumo`. No existen tablas ni SPs exclusivos por canal.
            """
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Correo enviado correctamente — `data` incluye mode, template, destinatarios y referencia Graph"),
        @ApiResponse(responseCode = "401", description = "JWT ausente/inválido o API Key ausente/inválida/de otro usuario"),
        @ApiResponse(responseCode = "403", description = "Usuario inactivo"),
        @ApiResponse(responseCode = "422", description = "Email destinatario inválido, campo requerido faltante o template no encontrado"),
        @ApiResponse(responseCode = "429", description = "Límite mensual de consumo alcanzado para el plan activo"),
        @ApiResponse(responseCode = "503", description = "Microsoft Graph no responde, clientSecret mal configurado en BD, o error en OAuth2")
    })
    @PostMapping("/enviar")
    public ResponseEntity<CorreoResponse> enviar(
            @Valid @RequestBody CorreoRequest request,
            Authentication auth) {
        int usuarioId = (int) auth.getPrincipal();
        return ResponseEntity.ok(correoService.enviar(usuarioId, request));
    }
}
