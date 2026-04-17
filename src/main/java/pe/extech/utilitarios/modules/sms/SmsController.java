package pe.extech.utilitarios.modules.sms;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import pe.extech.utilitarios.modules.sms.dto.SmsRequest;
import pe.extech.utilitarios.modules.sms.dto.SmsResponse;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/**
 * Controller REST para el servicio de envío de SMS.
 *
 * <p>
 * Expone un único endpoint POST {@code /api/v1/servicios/sms/enviar} que recibe
 * el
 * {@link pe.extech.utilitarios.modules.sms.dto.SmsRequest} validado con
 * {@code @Valid}, extrae
 * el {@code usuarioId} del {@code SecurityContext} (establecido previamente por
 * {@link pe.extech.utilitarios.security.JwtFilter}) y delega la lógica completa
 * a {@link ISmsService}.
 * </p>
 *
 * <p>
 * Este controller no contiene lógica de negocio: solo coordina la recepción del
 * request y la entrega del response. Toda validación de teléfono, resolución de
 * template,
 * llamada a Infobip y registro de consumo ocurren en {@link SmsService}.
 * </p>
 *
 * <p>
 * Autenticación: requiere {@code Authorization: Bearer <jwt>} y
 * {@code X-API-Key: <api_key>}
 * en cada request. Ambos headers son validados por los filtros de seguridad
 * antes de que
 * el request llegue aquí (R1).
 * </p>
 */
@Tag(name = "Servicios - SMS", description = "Envío de mensajes de texto vía Infobip. Requiere API Key.")
@SecurityRequirement(name = "apiKeyAuth")
@RestController
@RequestMapping("/api/v1/servicios/sms")
@RequiredArgsConstructor
public class SmsController {

    private final ISmsService smsService;

    @Operation(summary = "Enviar SMS", description = """
            Envía un mensaje de texto vía Infobip. Soporta dos modos de contenido: INLINE (texto libre) y TEMPLATE (plantilla almacenada en `IT_Template` con sustitución de variables `{{clave}}`).

            **Autenticación requerida (ambos headers obligatorios):**
            - `Authorization: Bearer <jwt>` — identifica al usuario y su plan activo.
            - `X-API-Key: <api_key>` — autoriza el consumo del servicio para ese usuario.
            Ambas credenciales deben corresponder al mismo usuario registrado.

            **Modos de envío:**
            - `INLINE`: incluir `"mode": "INLINE"` y `"message": "Texto del SMS"`.
            - `TEMPLATE`: incluir `"mode": "TEMPLATE"`, el objeto `"template"` con `"channel": "SMS"` y `"code": "<código>"`, y el mapa `"variables"` con los valores a sustituir (ej: `{"code": "1234", "minutes": 5}`).

            **Stored Procedures ejecutados internamente (en orden):**
            1. `uspUsuarioObtenerPorId(@UsuarioId)` — obtiene el nombre del usuario para registrarlo en la auditoría de `IT_Consumo`.
            2. `uspResolverApiExternaPorUsuarioYFuncion(@UsuarioId, 'SMS_SEND')` — resuelve desde `IT_ApiExternaFuncion` el `ApiServicesFuncionId`, la URL de Infobip, el token cifrado AES-256 y el template de autorización (`App {TOKEN}`). El token se descifra en tiempo de ejecución y **nunca se loguea**.
            3. `uspPlanValidarLimiteUsuario(@UsuarioId, @ApiServicesFuncionId)` — verifica si el usuario tiene consumos disponibles en su plan activo. Si ya alcanzó el límite mensual → registra el intento con `Exito=0` y retorna 429.
            4. Validación local del teléfono (formato `+51XXXXXXXXX` o `51XXXXXXXXX`) — si falla, se rechaza sin llamar a Infobip ni gastar consumo.
            5. Modo TEMPLATE: `uspTemplateObtenerAsunto(@ApiServicesFuncionId, @Codigo, @Version)` — obtiene el cuerpo de la plantilla desde `IT_Template` (Canal=SMS) y sustituye las variables `{{clave}}`. Si `@Version` es nulo, se usa la versión más reciente.
               Modo INLINE: el campo `message` del request se usa directamente como contenido del SMS.
            6. Llamada HTTP POST a Infobip con header `Authorization: App <token_descifrado>`. Timeout: configurable (default 30 s). La respuesta real incluye `messageId`, `statusId`, `statusName`, `statusGroupName` y `statusDescription`.
            7. `uspConsumoRegistrar(...)` — registra el resultado en `IT_Consumo` con `EsConsulta=0`. Se ejecuta **siempre**, tanto si el envío fue exitoso (`Exito=1`) como si falló (`Exito=0`). Solo los exitosos descuentan del límite mensual.

            **Regla R2:** 1 request = 1 registro en `IT_Consumo`, sin excepción.
            **Regla R4:** SMS y Correo comparten `IT_Template` e `IT_Consumo`. No existen tablas ni SPs exclusivos por canal.
            """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "SMS enviado correctamente — `data` incluye messageId y estado real de Infobip"),
            @ApiResponse(responseCode = "401", description = "JWT ausente/inválido o API Key ausente/inválida/de otro usuario"),
            @ApiResponse(responseCode = "403", description = "Usuario inactivo"),
            @ApiResponse(responseCode = "422", description = "Teléfono con formato incorrecto, campo requerido faltante o template no encontrado"),
            @ApiResponse(responseCode = "429", description = "Límite mensual de consumo alcanzado para el plan activo"),
            @ApiResponse(responseCode = "503", description = "Infobip no responde, token mal configurado en BD, o error de autenticación con el proveedor")
    })
    @PostMapping("/enviar")
    public ResponseEntity<SmsResponse> enviar(
            @Valid @RequestBody SmsRequest request,
            Authentication auth) {
        int usuarioId = (int) auth.getPrincipal();
        return ResponseEntity.ok(smsService.enviar(usuarioId, request));
    }
}
