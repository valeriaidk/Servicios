package pe.extech.utilitarios.modules.reniec.controllers;

import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import pe.extech.utilitarios.modules.reniec.application.interfaces.IReniecUseCases;
import pe.extech.utilitarios.modules.reniec.dto.ReniecResponse;

@Tag(name = "Servicios - RENIEC", description = "Consulta de personas por DNI. Requiere API Key.")
@SecurityRequirement(name = "apiKeyAuth")
@RestController
@RequestMapping("/api/v1/servicios/reniec")
@RequiredArgsConstructor
public class ReniecController {

    private final IReniecUseCases reniecService;

    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Consulta exitosa"),
            @ApiResponse(responseCode = "401", description = "JWT o API Key ausente/inválida"),
            @ApiResponse(responseCode = "422", description = "DNI con formato incorrecto"),
            @ApiResponse(responseCode = "429", description = "Límite mensual alcanzado"),
            @ApiResponse(responseCode = "503", description = "Proveedor externo no disponible")
    })
    @GetMapping("/dni")
    public ResponseEntity<ReniecResponse> consultarDni(
            @Parameter(description = "DNI de 8 dígitos", example = "72537503", required = true) @RequestParam("numero") String numero,
            Authentication auth) {

        int usuarioId = (int) auth.getPrincipal();
        return ResponseEntity.ok(reniecService.consultarDni(usuarioId, numero));
    }
}