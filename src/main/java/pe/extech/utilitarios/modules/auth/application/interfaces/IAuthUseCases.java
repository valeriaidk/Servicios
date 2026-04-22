package pe.extech.utilitarios.modules.auth.application.interfaces;

import pe.extech.utilitarios.modules.auth.dto.AuthResponse;
import pe.extech.utilitarios.modules.auth.dto.LoginRequest;
import pe.extech.utilitarios.modules.auth.dto.RegistroRequest;

/**
 * Contrato de los casos de uso de autenticación expuestos al controller.
 */
public interface IAuthUseCases {

    /**
     * Registra un nuevo usuario, le asigna el plan FREE y genera su API Key
     * inicial (entregada una única vez en la respuesta).
     */
    AuthResponse registrar(RegistroRequest request);

    /**
     * Valida credenciales y emite un JWT con el {@code planId} del usuario.
     */
    AuthResponse login(LoginRequest request);
}
