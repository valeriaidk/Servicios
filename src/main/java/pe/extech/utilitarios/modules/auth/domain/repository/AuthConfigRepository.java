package pe.extech.utilitarios.modules.auth.domain.repository;

import java.util.Map;

/**
 * Puerto de dominio para las consultas de configuración que necesita el flujo
 * de autenticación y la gestión del perfil del usuario.
 *
 * <p>
 * Encapsula las operaciones de lectura sobre {@code IT_Usuario},
 * {@code IT_Plan} e {@code IT_PlanFuncionLimite} necesarias para login,
 * perfil y resumen de consumo — todas vía Stored Procedures.
 * </p>
 *
 * <p>
 * El adaptador actual es {@code AuthRepositoryImpl} (JDBC).
 * </p>
 */
public interface AuthConfigRepository {

    /**
     * Retorna los datos del usuario por email para el proceso de login.
     * Devuelve mapa vacío si el usuario no existe o está inactivo/eliminado.
     */
    Map<String, Object> obtenerPorEmail(String email);

    /**
     * Retorna el plan activo del usuario (PlanId y Nombre). Mapa vacío si no
     * tiene plan activo.
     */
    Map<String, Object> obtenerPlanActivo(int usuarioId);

    /**
     * Retorna el límite mensual mínimo configurado para el plan (el más
     * restrictivo entre todas las funciones). {@code null} si el plan no
     * tiene límites configurados (ENTERPRISE).
     */
    Integer obtenerLimiteMensualPlan(int planId);

    /**
     * Retorna los datos del usuario por ID para la vista de perfil.
     */
    Map<String, Object> obtenerDatosUsuario(int usuarioId);
}
