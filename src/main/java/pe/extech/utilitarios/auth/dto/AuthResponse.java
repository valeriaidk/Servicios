package pe.extech.utilitarios.auth.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record AuthResponse(
        boolean ok,
        String jwt,
        String apiKey,
        UsuarioDto usuario,
        PlanDto plan,
        String mensaje
) {
    public record UsuarioDto(int usuarioId, String nombre, String apellido, String email) {}
    public record PlanDto(int planId, String nombre, int consumoActual) {}
}
