package pe.extech.utilitarios.sms;

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
import pe.extech.utilitarios.sms.dto.SmsRequest;
import pe.extech.utilitarios.sms.dto.SmsResponse;

@Tag(name = "Servicios - SMS", description = "Envío de mensajes de texto vía Infobip. Requiere API Key.")
@SecurityRequirement(name = "apiKeyAuth")
@RestController
@RequestMapping("/api/v1/servicios/sms")
@RequiredArgsConstructor
public class SmsController {

    private final ISmsService smsService;

    @Operation(
        summary = "Enviar SMS",
        description = """
            Requiere **Authorization: Bearer {jwt}** y **X-API-Key: {api_key}** en los headers.
            Descuenta 1 consumo del plan activo y registra en IT_Consumo (R2).

            **Modo INLINE**: `{ "mode": "INLINE", "to": "+51999999999", "message": "Texto" }`
            **Modo TEMPLATE**: `{ "mode": "TEMPLATE", "to": "+51...", "template": { "channel": "SMS", "code": "OTP" }, "variables": {...} }`

            La respuesta incluye datos reales de Infobip: messageId, statusName, statusDescription.
            """
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "SMS enviado correctamente"),
        @ApiResponse(responseCode = "401", description = "API Key inválida, inactiva o expirada"),
        @ApiResponse(responseCode = "403", description = "Usuario inactivo"),
        @ApiResponse(responseCode = "422", description = "Teléfono con formato incorrecto o campo requerido faltante"),
        @ApiResponse(responseCode = "429", description = "Límite de consumo del plan alcanzado"),
        @ApiResponse(responseCode = "503", description = "Proveedor Infobip no disponible")
    })
    @PostMapping("/enviar")
    public ResponseEntity<SmsResponse> enviar(
            @Valid @RequestBody SmsRequest request,
            Authentication auth) {
        int usuarioId = (int) auth.getPrincipal();
        return ResponseEntity.ok(smsService.enviar(usuarioId, request));
    }
}
