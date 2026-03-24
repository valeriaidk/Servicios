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

@Tag(name = "Servicios - Correo", description = "Envío de correo electrónico. Requiere JWT + API Key.")
@SecurityRequirement(name = "bearerAuth")
@SecurityRequirement(name = "apiKeyAuth")
@RestController
@RequestMapping("/api/v1/servicios/correo")
@RequiredArgsConstructor
public class CorreoController {

    private final ICorreoService correoService;

    @Operation(
        summary = "Enviar correo electrónico",
        description = """
            Requiere **X-API-Key** en el header `X-API-Key`. JWT no es válido aquí.
            Descuenta 1 consumo del plan activo.

            **Modo Template**: Indicar `template` (código de IT_Template canal EMAIL) y
            `variables` (mapa clave→valor para sustitución `{{variable}}`).
            **Modo Inline**: Indicar `asunto` y `cuerpoHtml` (y opcionalmente `cuerpoTexto`).

            Cada request registra 1 consumo en IT_Consumo (R2).
            """
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Correo enviado correctamente"),
        @ApiResponse(responseCode = "401", description = "API Key inválida, inactiva o expirada"),
        @ApiResponse(responseCode = "403", description = "Usuario inactivo"),
        @ApiResponse(responseCode = "422", description = "Email destinatario inválido o campo requerido faltante"),
        @ApiResponse(responseCode = "429", description = "Límite de consumo del plan alcanzado"),
        @ApiResponse(responseCode = "503", description = "Servidor SMTP / SendGrid no disponible")
    })
    @PostMapping("/enviar")
    public ResponseEntity<CorreoResponse> enviar(
            @Valid @RequestBody CorreoRequest request,
            Authentication auth) {
        int usuarioId = (int) auth.getPrincipal();
        return ResponseEntity.ok(correoService.enviar(usuarioId, request));
    }
}
