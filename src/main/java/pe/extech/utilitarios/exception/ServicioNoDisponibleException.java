package pe.extech.utilitarios.exception;

public class ServicioNoDisponibleException extends RuntimeException {
    public ServicioNoDisponibleException(String proveedor) {
        super("El proveedor externo " + proveedor + " no está disponible. Intenta nuevamente.");
    }
}
