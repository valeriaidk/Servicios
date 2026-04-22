package pe.extech.utilitarios.modules.sunat.controllers;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import pe.extech.utilitarios.modules.sunat.application.interfaces.ISunatUseCases;
import pe.extech.utilitarios.modules.sunat.dto.SunatResponse;

/**
 * Endpoint REST del módulo SUNAT. Delega toda la lógica al caso de uso
 * {@link ISunatUseCases}. No contiene reglas de negocio.
 *
 * <p>
 * Autenticación requerida en cada request:
 * {@code Authorization: Bearer <jwt>} y {@code X-API-Key: <api_key>}.
 * Ambos headers se validan en los filtros de seguridad antes de llegar aquí.
 * </p>
 */
@Tag(name = "Servicios - SUNAT", description = "Consulta de contribuyentes por RUC. Requiere API Key.")
@SecurityRequirement(name = "apiKeyAuth")
@RestController
@RequestMapping("/api/v1/servicios/sunat")
@RequiredArgsConstructor
public class SunatController {

    private final ISunatUseCases sunatUseCases;

    @Operation(summary = "Consultar contribuyente por RUC",
            description = "Consulta en SUNAT los datos tributarios de un contribuyente a partir de su RUC. "
                    + "Retorna razón social, estado, condición, dirección fiscal y datos económicos.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Consulta exitosa"),
            @ApiResponse(responseCode = "401", description = "JWT o API Key ausente/inválida"),
            @ApiResponse(responseCode = "422", description = "RUC con formato incorrecto"),
            @ApiResponse(responseCode = "429", description = "Límite mensual alcanzado"),
            @ApiResponse(responseCode = "503", description = "Proveedor externo no disponible")
    })
    @GetMapping("/ruc")
    public ResponseEntity<SunatResponse> consultarRuc(
            @Parameter(description = "RUC de 11 dígitos (prefijo 10 o 20)", example = "20100070970",
                    required = true) @RequestParam("numero") String numero,
            Authentication auth) {

        int usuarioId = (int) auth.getPrincipal();
        return ResponseEntity.ok(sunatUseCases.consultarRuc(usuarioId, numero));
    }
}
