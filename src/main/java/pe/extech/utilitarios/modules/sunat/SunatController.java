package pe.extech.utilitarios.modules.sunat;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import pe.extech.utilitarios.modules.sunat.dto.SunatResponse;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/**
 * Controller REST para el servicio de consulta SUNAT.
 *
 * <p>
 * Expone un único endpoint GET {@code /api/v1/servicios/sunat/ruc} que recibe
 * el RUC
 * como query param, extrae el {@code usuarioId} del {@code SecurityContext}
 * (establecido
 * previamente por {@link pe.extech.utilitarios.security.JwtFilter}) y delega la
 * lógica
 * completa a {@link ISunatService}.
 * </p>
 *
 * <p>
 * Este controller no contiene lógica de negocio: solo valida que el parámetro
 * {@code numero} no sea nulo/vacío y traduce la excepción a HTTP si lo fuera.
 * Toda validación de formato, consulta al proveedor y registro de consumo
 * ocurren
 * en {@link SunatService}.
 * </p>
 *
 * <p>
 * Autenticación: requiere {@code Authorization: Bearer <jwt>} y
 * {@code X-API-Key: <api_key>}
 * en cada request. Ambos headers son validados por los filtros de seguridad
 * antes de que
 * el request llegue aquí (R1).
 * </p>
 */
@Tag(name = "Servicios - SUNAT", description = "Consulta de contribuyentes por RUC. Requiere API Key.")
@SecurityRequirement(name = "apiKeyAuth")
@RestController
@RequestMapping("/api/v1/servicios/sunat")
@RequiredArgsConstructor
public class SunatController {

    private final ISunatService sunatService;

    @Operation(summary = "Consultar contribuyente por RUC", description = """
            Consulta los datos de un contribuyente en SUNAT a través del proveedor Decolecta. Incluye razón social, estado tributario, condición (habido/no habido), dirección fiscal completa y datos de actividad económica.

            **Autenticación requerida (ambos headers obligatorios):**
            - `Authorization: Bearer <jwt>` — identifica al usuario y su plan activo.
            - `X-API-Key: <api_key>` — autoriza el consumo del servicio para ese usuario.
            Ambas credenciales deben corresponder al mismo usuario registrado.

            **Parámetro de consulta:**
            - `?numero=<ruc>` — RUC de exactamente 11 dígitos que comience con `10` (persona natural) o `20` (persona jurídica). Ej: `20100070970`.

            **Stored Procedures ejecutados internamente (en orden):**
            1. `uspUsuarioObtenerPorId(@UsuarioId)` — obtiene el nombre del usuario para registrarlo en la auditoría de `IT_Consumo`.
            2. Validación local del RUC (11 dígitos, prefijo 10 o 20) — si falla, se rechaza sin tocar el proveedor ni gastar consumo.
            3. `uspResolverApiExternaPorUsuarioYFuncion(@UsuarioId, 'SUNAT_RUC')` — resuelve desde `IT_ApiExternaFuncion` el `ApiServicesFuncionId`, la URL del proveedor Decolecta y el token cifrado AES-256. El token se descifra en tiempo de ejecución y **nunca se loguea**.
            4. `uspPlanValidarLimiteUsuario(@UsuarioId, @ApiServicesFuncionId)` — verifica si el usuario tiene consumos disponibles en su plan. Si ya alcanzó el límite mensual → registra el intento con `Exito=0` y retorna 429.
            5. Llamada HTTP GET a Decolecta con `Authorization: Bearer <token_descifrado>`. Timeout: 60 s (configurable).
            6. `uspConsumoRegistrar(...)` — registra el resultado en `IT_Consumo` con `EsConsulta=1`. Se ejecuta **siempre**, tanto si la consulta fue exitosa (`Exito=1`) como si falló (`Exito=0`). Solo los exitosos descuentan del límite mensual.

            **Regla R2:** 1 request = 1 registro en `IT_Consumo`, sin excepción.
            **Nota:** si el RUC existe en el formato correcto pero SUNAT no tiene datos para él, Decolecta retorna una respuesta con los campos vacíos. El consumo se registra igual.
            """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Consulta exitosa — datos del contribuyente retornados en `data`"),
            @ApiResponse(responseCode = "401", description = "JWT ausente/inválido o API Key ausente/inválida/de otro usuario"),
            @ApiResponse(responseCode = "422", description = "RUC con formato incorrecto (debe tener 11 dígitos y comenzar con 10 o 20)"),
            @ApiResponse(responseCode = "429", description = "Límite mensual de consumo alcanzado para el plan activo"),
            @ApiResponse(responseCode = "503", description = "Decolecta no responde, token mal configurado en BD, o error de autenticación con el proveedor")
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
