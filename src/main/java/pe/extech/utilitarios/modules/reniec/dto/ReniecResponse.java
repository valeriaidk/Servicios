package pe.extech.utilitarios.modules.reniec.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReniecResponse(
                boolean ok,
                String codigo,
                String mensaje,
                Integer usuarioId,
                String nombreUsuario,
                String plan,
                Integer consumoActual,
                Integer limiteMaximo,
                Integer apiServicesFuncionId,
                // Información del servicio consumido (RENIEC_DNI)
                String servicioNombre,
                String servicioCodigo,
                String servicioDescripcion,
                ReniecData data) {
        public record ReniecData(
                        String dni,
                        String nombres,
                        String apellidoPaterno,
                        String apellidoMaterno,
                        String nombreCompleto) {
        }
}
