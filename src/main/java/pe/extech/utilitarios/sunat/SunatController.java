package pe.extech.utilitarios.sunat;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import pe.extech.utilitarios.sunat.dto.SunatResponse;

@Tag(name = "Servicios - SUNAT", description = "Consulta de contribuyentes por RUC. Requiere API Key.")
@SecurityRequirement(name = "apiKeyAuth")
@RestController
@RequestMapping("/api/v1/servicios/sunat")
@RequiredArgsConstructor
public class SunatController {

    private final ISunatService sunatService;

    @Operation(
        summary = "Consultar contribuyente por RUC",
        description = """
            Consulta los datos de un contribuyente en SUNAT vía Decolecta.

            **Autenticación requerida (ambos headers obligatorios):**
            - `Authorization: Bearer <jwt>` — identifica al usuario y su plan.
            - `X-API-Key: <api_key>` — autoriza el consumo del servicio.

            El RUC debe tener 11 dígitos y comenzar con 10 o 20.
            Descuenta 1 consumo del plan activo (incluso si el RUC no existe en SUNAT).
            """
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Consulta exitosa"),
        @ApiResponse(responseCode = "401", description = "JWT inválido o API Key inválida"),
        @ApiResponse(responseCode = "422", description = "RUC con formato incorrecto"),
        @ApiResponse(responseCode = "429", description = "Límite de consumo alcanzado"),
        @ApiResponse(responseCode = "503", description = "Proveedor externo no disponible")
    })
    @GetMapping("/ruc")
    public ResponseEntity<SunatResponse> consultarRuc(
            @RequestParam("numero") String numero,
            Authentication auth) {

        if (numero == null || numero.isBlank()) {
            throw new IllegalArgumentException("El parámetro 'numero' es obligatorio.");
        }
        int usuarioId = (int) auth.getPrincipal();
        return ResponseEntity.ok(sunatService.consultarRuc(usuarioId, numero));
    }
}
