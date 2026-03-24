package pe.extech.utilitarios.reniec;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import pe.extech.utilitarios.reniec.dto.ReniecResponse;

@Tag(name = "Servicios - RENIEC", description = "Consulta de personas por DNI. Requiere API Key.")
@SecurityRequirement(name = "apiKeyAuth")
@RestController
@RequestMapping("/api/v1/servicios/reniec")
@RequiredArgsConstructor
public class ReniecController {

    private final IReniecService reniecService;

    @Operation(
        summary = "Consultar persona por DNI",
        description = """
            Requiere **dos headers**:
            - `Authorization: Bearer <jwt>` — identifica al usuario y su plan.
            - `X-API-Key: <clave>` — autoriza el consumo del servicio.

            Ambas credenciales deben corresponder al mismo usuario.
            Descuenta 1 consumo del plan activo al mes.
            """
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Consulta exitosa"),
        @ApiResponse(responseCode = "401", description = "JWT ausente/inválido o API Key ausente/inválida"),
        @ApiResponse(responseCode = "422", description = "DNI con formato incorrecto (debe tener 8 dígitos)"),
        @ApiResponse(responseCode = "429", description = "Límite mensual de consumo alcanzado"),
        @ApiResponse(responseCode = "503", description = "Error de autenticación con el proveedor externo o servicio no disponible")
    })
    @GetMapping("/dni")
    public ResponseEntity<ReniecResponse> consultarDni(
            @Parameter(description = "DNI de 8 dígitos a consultar", example = "72537503", required = true)
            @RequestParam("numero") String numero,
            Authentication auth) {

        if (numero == null || numero.isBlank()) {
            throw new IllegalArgumentException("El parámetro 'numero' es obligatorio.");
        }

        int usuarioId = (int) auth.getPrincipal();
        return ResponseEntity.ok(reniecService.consultarDni(usuarioId, numero));
    }
}
