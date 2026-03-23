package pe.extech.utilitarios.exception;

/**
 * Excepción lanzada cuando un proveedor externo (Decolecta, Infobip, etc.)
 * responde con un código HTTP de error (4xx, 5xx).
 *
 * Transporta el código de estado real del proveedor y el body de su respuesta
 * para que GlobalExceptionHandler pueda devolver al cliente una respuesta
 * diferenciada en lugar del genérico 503 SERVICIO_NO_DISPONIBLE.
 */
public class ProveedorExternoException extends RuntimeException {

    private final String proveedor;
    private final int statusProveedor;
    private final String bodyProveedor;

    public ProveedorExternoException(String proveedor, int statusProveedor, String bodyProveedor) {
        super("El proveedor " + proveedor + " respondió HTTP " + statusProveedor + ".");
        this.proveedor       = proveedor;
        this.statusProveedor = statusProveedor;
        this.bodyProveedor   = bodyProveedor;
    }

    public String getProveedor()       { return proveedor; }
    public int    getStatusProveedor() { return statusProveedor; }
    public String getBodyProveedor()   { return bodyProveedor; }
}
