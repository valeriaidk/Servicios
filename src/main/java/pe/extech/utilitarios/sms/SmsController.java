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

    private final SmsService smsService;

    @Operation(
        summary = "Enviar SMS",
        description = """
            Requiere **X-API-Key** en el header `X-API-Key`. JWT no es válido aquí.
            Descuenta 1 consumo del plan activo.

            **Modo Template**: Indicar `template` (código de IT_Template) y `variables` (mapa clave→valor).
            **Modo Inline**: Indicar `mensaje` directamente.

            Si el mismo request idéntico se repite en < 30 s, se retorna la respuesta
            cacheada sin consumo adicional (deduplicación por R9).
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
