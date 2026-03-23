package pe.extech.utilitarios.sunat;

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
import pe.extech.utilitarios.sunat.dto.SunatRequest;
import pe.extech.utilitarios.sunat.dto.SunatResponse;

@Tag(name = "Servicios - SUNAT", description = "Consulta de contribuyentes por RUC. Requiere API Key.")
@SecurityRequirement(name = "apiKeyAuth")
@RestController
@RequestMapping("/api/v1/servicios/sunat")
@RequiredArgsConstructor
public class SunatController {

    private final SunatService sunatService;

    @Operation(
        summary = "Consultar contribuyente por RUC",
        description = "Requiere **X-API-Key** en el header `X-API-Key`. JWT no es válido aquí. " +
                      "Descuenta 1 consumo del plan activo. RUC debe tener 11 dígitos y comenzar con 10 o 20."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Consulta exitosa"),
        @ApiResponse(responseCode = "401", description = "API Key inválida o expirada"),
        @ApiResponse(responseCode = "422", description = "RUC con formato incorrecto"),
        @ApiResponse(responseCode = "429", description = "Límite de consumo alcanzado"),
        @ApiResponse(responseCode = "503", description = "Proveedor externo no disponible")
    })
    @PostMapping("/ruc")
    public ResponseEntity<SunatResponse> consultarRuc(
            @Valid @RequestBody SunatRequest request,
            Authentication auth) {
        int usuarioId = (int) auth.getPrincipal();
        return ResponseEntity.ok(sunatService.consultarRuc(usuarioId, request));
    }
}
