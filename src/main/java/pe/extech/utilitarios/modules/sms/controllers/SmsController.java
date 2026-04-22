package pe.extech.utilitarios.modules.sms.controllers;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import pe.extech.utilitarios.modules.sms.application.interfaces.ISmsUseCases;
import pe.extech.utilitarios.modules.sms.dto.SmsRequest;
import pe.extech.utilitarios.modules.sms.dto.SmsResponse;

/**
 * Endpoint REST del módulo SMS. Delega toda la lógica al caso de uso
 * {@link ISmsUseCases}. No contiene reglas de negocio.
 *
 * <p>
 * Autenticación requerida: {@code Authorization: Bearer <jwt>} y
 * {@code X-API-Key: <api_key>}.
 * </p>
 */
@Tag(name = "Servicios - SMS", description = "Envío de mensajes de texto vía Infobip. Requiere API Key.")
@SecurityRequirement(name = "apiKeyAuth")
@RestController
@RequestMapping("/api/v1/servicios/sms")
@RequiredArgsConstructor
public class SmsController {

    private final ISmsUseCases smsUseCases;

    @Operation(summary = "Enviar SMS",
            description = "Envía un mensaje de texto vía Infobip. Soporta dos modos: INLINE (texto libre) y "
                    + "TEMPLATE (plantilla en classpath o IT_Template con sustitución de variables).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "SMS enviado correctamente"),
            @ApiResponse(responseCode = "401", description = "JWT o API Key ausente/inválida"),
            @ApiResponse(responseCode = "403", description = "Usuario inactivo"),
            @ApiResponse(responseCode = "422", description = "Teléfono con formato incorrecto o campos faltantes"),
            @ApiResponse(responseCode = "429", description = "Límite mensual alcanzado"),
            @ApiResponse(responseCode = "503", description = "Proveedor externo no disponible")
    })
    @PostMapping("/enviar")
    public ResponseEntity<SmsResponse> enviar(
            @Valid @RequestBody SmsRequest request,
            Authentication auth) {
        int usuarioId = (int) auth.getPrincipal();
        return ResponseEntity.ok(smsUseCases.enviar(usuarioId, request));
    }
}
