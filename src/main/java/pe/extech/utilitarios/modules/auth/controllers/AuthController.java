package pe.extech.utilitarios.modules.auth.controllers;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import pe.extech.utilitarios.modules.auth.application.interfaces.IAuthUseCases;
import pe.extech.utilitarios.modules.auth.dto.AuthResponse;
import pe.extech.utilitarios.modules.auth.dto.LoginRequest;
import pe.extech.utilitarios.modules.auth.dto.RegistroRequest;

/**
 * Endpoints públicos de registro y login.
 */
@Tag(name = "Auth", description = "Registro y login de usuarios")
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final IAuthUseCases authUseCases;

    @Operation(summary = "Registrar nuevo usuario",
            description = "Crea el usuario, asigna plan FREE y genera el API Key (solo se muestra una vez).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Usuario registrado correctamente"),
            @ApiResponse(responseCode = "409", description = "El correo ya está registrado"),
            @ApiResponse(responseCode = "422", description = "Datos de entrada inválidos")
    })
    @PostMapping("/registro")
    public ResponseEntity<AuthResponse> registro(@Valid @RequestBody RegistroRequest request) {
        return ResponseEntity.ok(authUseCases.registrar(request));
    }

    @Operation(summary = "Login",
            description = "Autenticación con email y contraseña. Retorna JWT y metadatos del plan activo.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Login exitoso"),
            @ApiResponse(responseCode = "401", description = "Credenciales inválidas"),
            @ApiResponse(responseCode = "403", description = "Usuario inactivo")
    })
    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(authUseCases.login(request));
    }
}
