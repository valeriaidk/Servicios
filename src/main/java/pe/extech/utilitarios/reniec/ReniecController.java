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

/**
 * Controller REST para el servicio de consulta RENIEC.
 *
 * <p>Expone un único endpoint GET {@code /api/v1/servicios/reniec/dni} que recibe el DNI
 * como query param, extrae el {@code usuarioId} del {@code SecurityContext} (establecido
 * previamente por {@link pe.extech.utilitarios.security.JwtFilter}) y delega la lógica
 * completa a {@link IReniecService}.</p>
 *
 * <p>Este controller no contiene lógica de negocio: solo valida que el parámetro
 * {@code numero} no sea nulo/vacío y traduce la excepción a HTTP si lo fuera.
 * Toda validación de formato, consulta al proveedor y registro de consumo ocurren
 * en {@link ReniecService}.</p>
 *
 * <p>Autenticación: requiere {@code Authorization: Bearer <jwt>} y {@code X-API-Key: <api_key>}
 * en cada request. Ambos headers son validados por los filtros de seguridad antes de que
 * el request llegue aquí (R1).</p>
 */
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
            Consulta los datos personales de una persona en RENIEC a través del proveedor Decolecta.

            **Autenticación requerida (ambos headers obligatorios):**
            - `Authorization: Bearer <jwt>` — identifica al usuario y su plan activo.
            - `X-API-Key: <api_key>` — autoriza el consumo del servicio para ese usuario.
            Ambas credenciales deben corresponder al mismo usuario registrado.

            **Parámetro de consulta:**
            - `?numero=<dni>` — DNI de exactamente 8 dígitos numéricos (ej: `72537503`).

            **Stored Procedures ejecutados internamente (en orden):**
            1. `uspUsuarioObtenerPorId(@UsuarioId)` — obtiene el nombre del usuario para registrarlo en la auditoría de `IT_Consumo`.
            2. Validación local del DNI (8 dígitos numéricos) — si falla, se rechaza antes de tocar la BD o el proveedor, sin gastar consumo.
            3. `uspResolverApiExternaPorUsuarioYFuncion(@UsuarioId, 'RENIEC_DNI')` — resuelve desde `IT_ApiExternaFuncion` el `ApiServicesFuncionId`, la URL del proveedor Decolecta y el token cifrado AES-256. El token se descifra en tiempo de ejecución y **nunca se loguea**.
            4. `uspPlanValidarLimiteUsuario(@UsuarioId, @ApiServicesFuncionId)` — verifica si el usuario tiene consumos disponibles en su plan activo. Si ya alcanzó el límite mensual → registra el intento con `Exito=0` y retorna 429.
            5. Llamada HTTP GET a Decolecta con `Authorization: Bearer <token_descifrado>`. Timeout: 60 s (configurable).
            6. `uspConsumoRegistrar(...)` — registra el resultado en `IT_Consumo` con `EsConsulta=1`. Se ejecuta **siempre**, tanto si la consulta fue exitosa (`Exito=1`) como si falló (`Exito=0`). Solo los exitosos descuentan del límite mensual.

            **Regla R2:** 1 request = 1 registro en `IT_Consumo`, sin excepción.
            """
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Consulta exitosa — datos de la persona retornados en `data`"),
        @ApiResponse(responseCode = "401", description = "JWT ausente/inválido o API Key ausente/inválida/de otro usuario"),
        @ApiResponse(responseCode = "422", description = "DNI con formato incorrecto (debe tener exactamente 8 dígitos numéricos)"),
        @ApiResponse(responseCode = "429", description = "Límite mensual de consumo alcanzado para el plan activo"),
        @ApiResponse(responseCode = "503", description = "Decolecta no responde, token mal configurado en BD, o error de autenticación con el proveedor")
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
