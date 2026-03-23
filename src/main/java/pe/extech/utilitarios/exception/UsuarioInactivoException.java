package pe.extech.utilitarios.exception;

public class UsuarioInactivoException extends RuntimeException {
    public UsuarioInactivoException() {
        super("La cuenta de usuario está desactivada.");
    }
}
