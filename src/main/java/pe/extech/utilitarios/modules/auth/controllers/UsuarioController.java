package pe.extech.utilitarios.modules.auth.controllers;

import io.jsonwebtoken.Claims;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import pe.extech.utilitarios.modules.auth.application.interfaces.IUsuarioUseCases;
import pe.extech.utilitarios.security.JwtUtil;

import java.util.Map;

/**
 * Endpoints del usuario autenticado: perfil, rotación de API Key, cambio de
 * plan y consultas de consumo. Todos requieren JWT.
 */
@Tag(name = "Usuario", description = "Perfil, API Key y plan del usuario autenticado")
@RestController
@RequestMapping("/api/v1/usuario")
@RequiredArgsConstructor
public class UsuarioController {

    private final IUsuarioUseCases usuarioUseCases;
    private final JwtUtil jwtUtil;

    @Operation(summary = "Obtener perfil del usuario autenticado",
            description = "Requiere JWT en el header Authorization: Bearer {token}.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Perfil retornado correctamente"),
            @ApiResponse(responseCode = "401", description = "JWT expirado o inválido")
    })
    @GetMapping("/perfil")
    public ResponseEntity<Map<String, Object>> perfil(Authentication auth) {
        int userId = (int) auth.getPrincipal();
        return ResponseEntity.ok(usuarioUseCases.obtenerPerfil(userId));
    }

    @Operation(summary = "Regenerar API Key manualmente",
            description = "Llama a uspApiKeyDesactivarYCrear. El API Key anterior queda inválido de inmediato. "
                    + "El nuevo valor solo se muestra en esta respuesta. El API Key se almacena como hash BCrypt "
                    + "y no puede recuperarse: guarda el valor retornado.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Nuevo API Key generado"),
            @ApiResponse(responseCode = "401", description = "JWT expirado o inválido")
    })
    @PostMapping("/api-key/regenerar")
    public ResponseEntity<Map<String, Object>> regenerarApiKey(Authentication auth) {
        int userId = (int) auth.getPrincipal();
        String nuevoApiKey = usuarioUseCases.regenerarApiKey(userId);
        return ResponseEntity.ok(Map.of(
                "ok", true,
                "apiKey", nuevoApiKey,
                "mensaje", "API Key regenerado. Guarda este valor: no se mostrará nuevamente."));
    }

    @Operation(summary = "Cambiar plan de suscripción",
            description = "Llama a uspPlanUsuarioCambiar. Retorna un nuevo JWT con el planId actualizado. "
                    + "Body: { \"planId\": <int> }")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Plan cambiado y nuevo JWT emitido"),
            @ApiResponse(responseCode = "401", description = "JWT expirado o inválido"),
            @ApiResponse(responseCode = "404", description = "El plan especificado no existe o está inactivo")
    })
    @PostMapping("/cambiar-plan")
    public ResponseEntity<Map<String, Object>> cambiarPlan(
            Authentication auth,
            @RequestBody Map<String, Integer> body) {
        int userId = (int) auth.getPrincipal();
        int nuevoPlanId = body.get("planId");
        Claims claims = (Claims) auth.getDetails();
        String email = jwtUtil.extraerEmail(claims);
        return ResponseEntity.ok(usuarioUseCases.cambiarPlan(userId, nuevoPlanId, email));
    }

    @Operation(summary = "Historial paginado de consumos",
            description = "Parámetros opcionales: page (default 1) y size (default 20).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Historial retornado"),
            @ApiResponse(responseCode = "401", description = "JWT expirado o inválido")
    })
    @GetMapping("/consumo")
    public ResponseEntity<Map<String, Object>> historialConsumo(
            Authentication auth,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        int userId = (int) auth.getPrincipal();
        return ResponseEntity.ok(usuarioUseCases.obtenerHistorial(userId, page, size));
    }

    @Operation(summary = "Resumen del consumo mensual actual vs límite del plan",
            description = "Retorna consumo actual, plan y límite máximo.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Resumen retornado"),
            @ApiResponse(responseCode = "401", description = "JWT expirado o inválido")
    })
    @GetMapping("/consumo/resumen")
    public ResponseEntity<Map<String, Object>> resumenConsumo(Authentication auth) {
        int userId = (int) auth.getPrincipal();
        return ResponseEntity.ok(usuarioUseCases.obtenerResumenConsumo(userId));
    }
}
