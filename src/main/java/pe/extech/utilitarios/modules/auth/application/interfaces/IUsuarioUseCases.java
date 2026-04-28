package pe.extech.utilitarios.modules.auth.application.interfaces;

import java.util.Map;

/**
 * Contrato de los casos de uso de gestión del usuario autenticado:
 * perfil, rotación de API Key, cambio de plan y consultas de consumo.
 */
public interface IUsuarioUseCases {

    Map<String, Object> obtenerPerfil(int usuarioId);

    String regenerarApiKey(int usuarioId);

    Map<String, Object> cambiarPlan(int usuarioId, int nuevoPlanId, String email);

    Map<String, Object> obtenerHistorial(int usuarioId, int page, int size);

    Map<String, Object> obtenerResumenConsumo(int usuarioId);
}
