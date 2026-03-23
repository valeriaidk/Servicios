package pe.extech.utilitarios.auth.dto;

import jakarta.validation.constraints.*;

public record RegistroRequest(
        @NotBlank(message = "El nombre es obligatorio")
        String nombre,

        @NotBlank(message = "El apellido es obligatorio")
        String apellido,

        @NotBlank(message = "El correo es obligatorio")
        @Email(message = "El correo no tiene un formato válido")
        String email,

        @NotBlank(message = "La contraseña es obligatoria")
        @Size(min = 8, message = "La contraseña debe tener al menos 8 caracteres")
        @Pattern(regexp = ".*[A-Z].*", message = "La contraseña debe tener al menos una mayúscula")
        @Pattern(regexp = ".*[0-9].*", message = "La contraseña debe tener al menos un número")
        String password,

        String telefono,
        String razonSocial,
        String ruc
) {}
