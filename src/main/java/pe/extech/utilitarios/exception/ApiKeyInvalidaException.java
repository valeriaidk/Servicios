package pe.extech.utilitarios.exception;

public class ApiKeyInvalidaException extends RuntimeException {
    public ApiKeyInvalidaException() {
        super("API Key inválida, inactiva o expirada.");
    }
    public ApiKeyInvalidaException(String mensaje) {
        super(mensaje);
    }
}
