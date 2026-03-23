package pe.extech.utilitarios.exception;

import java.util.Map;

public class LimiteAlcanzadoException extends RuntimeException {

    private final Map<String, Object> detalles;

    public LimiteAlcanzadoException(String mensaje, int consumoActual, int limiteMaximo, String plan) {
        super(mensaje);
        this.detalles = Map.of(
                "consumoActual", consumoActual,
                "limiteMaximo", limiteMaximo,
                "plan", plan
        );
    }

    public Map<String, Object> getDetalles() {
        return detalles;
    }
}
