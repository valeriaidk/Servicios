package pe.extech.utilitarios.exception;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(
        boolean ok,
        String codigo,
        String mensaje,
        Map<String, Object> detalles
) {
    public ErrorResponse(String codigo, String mensaje) {
        this(false, codigo, mensaje, null);
    }

    public ErrorResponse(String codigo, String mensaje, Map<String, Object> detalles) {
        this(false, codigo, mensaje, detalles);
    }
}
