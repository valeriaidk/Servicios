package pe.extech.utilitarios.modules.correo.controllers;

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
import pe.extech.utilitarios.modules.correo.application.interfaces.ICorreoUseCases;
import pe.extech.utilitarios.modules.correo.dto.CorreoRequest;
import pe.extech.utilitarios.modules.correo.dto.CorreoResponse;

/**
 * Endpoint REST del módulo Correo. Delega toda la lógica al caso de uso
 * {@link ICorreoUseCases}. No contiene reglas de negocio.
 *
 * <p>
 * Autenticación: {@code Authorization: Bearer <jwt>} y
 * {@code X-API-Key: <api_key>} validados por los filtros de seguridad.
 * </p>
 */
@Tag(name = "Servicios - Correo",
        description = "Envío de correo electrónico vía Microsoft Graph. Requiere API Key.")
@SecurityRequirement(name = "apiKeyAuth")
@RestController
@RequestMapping("/api/v1/servicios/correo")
@RequiredArgsConstructor
public class CorreoController {

    private final ICorreoUseCases correoUseCases;

    @Operation(summary = "Enviar correo electrónico",
            description = "Envía un correo vía Microsoft Graph. Modos INLINE (subject + body_html directos) o "
                    + "TEMPLATE (plantilla HTML + asunto de IT_Template con variables).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Correo enviado correctamente"),
            @ApiResponse(responseCode = "401", description = "JWT o API Key ausente/inválida"),
            @ApiResponse(responseCode = "403", description = "Usuario inactivo"),
            @ApiResponse(responseCode = "422", description = "Correo con formato incorrecto o campos faltantes"),
            @ApiResponse(responseCode = "429", description = "Límite mensual alcanzado"),
            @ApiResponse(responseCode = "503", description = "Proveedor externo no disponible")
    })
    @PostMapping("/enviar")
    public ResponseEntity<CorreoResponse> enviar(
            @Valid @RequestBody CorreoRequest request,
            Authentication auth) {
        int usuarioId = (int) auth.getPrincipal();
        return ResponseEntity.ok(correoUseCases.enviar(usuarioId, request));
    }
}
