package pe.extech.utilitarios.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import pe.extech.utilitarios.exception.ErrorResponse;

import java.io.IOException;

/**
 * Filtro API Key para /servicios/**.
 *
 * Nuevo flujo dual (JWT + API Key):
 *   1. JwtFilter corre primero y establece userId en SecurityContext.
 *   2. Este filtro (ApiKeyFilter) corre después y:
 *      a) Exige que X-API-Key esté presente en el header.
 *      b) Resuelve el userId propietario de ese API Key (BCrypt — R8).
 *      c) Verifica que coincida con el userId del JWT.
 *      d) Si todo es correcto → continúa. La autenticación del SecurityContext
 *         ya fue establecida por JwtFilter (userId, rol CLIENTE/ADMIN).
 *
 * Rechazos:
 *   - Sin JWT válido previo   → 401 JWT_REQUERIDO
 *   - Sin X-API-Key           → 401 API_KEY_INVALIDA
 *   - API Key inválida/expirada → 401 API_KEY_INVALIDA
 *   - API Key no corresponde al usuario del JWT → 401 API_KEY_INVALIDA
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ApiKeyFilter extends OncePerRequestFilter {

    private final ApiKeyUtil apiKeyUtil;
    private final ObjectMapper objectMapper;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // Solo aplica a /servicios/**
        return !request.getRequestURI().startsWith("/api/v1/servicios/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        // ── Paso 1: JWT debe haber corrido antes y establecido el usuario ──────
        // JwtFilter corre antes que este filtro (ver SecurityConfig).
        // Si el JWT era inválido o estaba ausente, SecurityContext no tendrá autenticación.
        Authentication jwtAuth = SecurityContextHolder.getContext().getAuthentication();
        if (jwtAuth == null || !jwtAuth.isAuthenticated()
                || !(jwtAuth.getPrincipal() instanceof Integer)) {
            rechazarConCodigo(response, "JWT_REQUERIDO",
                    "Se requiere un JWT válido en Authorization: Bearer <token>. " +
                    "Obtén un JWT con POST /auth/login.");
            return;
        }
        int jwtUserId = (Integer) jwtAuth.getPrincipal();

        // ── Paso 2: X-API-Key obligatorio ────────────────────────────────────
        String apiKeyPlano = request.getHeader("X-API-Key");
        if (apiKeyPlano == null || apiKeyPlano.isBlank()) {
            rechazarConCodigo(response, "API_KEY_INVALIDA",
                    "Se requiere el header X-API-Key para consumir servicios.");
            return;
        }

        // ── Paso 3: validar API Key y verificar que corresponde al usuario JWT ─
        try {
            Integer apiKeyUserId = apiKeyUtil.resolverUsuarioId(apiKeyPlano);
            if (apiKeyUserId == null) {
                rechazarConCodigo(response, "API_KEY_INVALIDA",
                        "API Key inválida o expirada.");
                return;
            }
            if (!apiKeyUserId.equals(jwtUserId)) {
                // La API Key pertenece a un usuario distinto al del JWT.
                // Loguear como WARN (posible intento de uso cruzado de credenciales).
                log.warn("[ApiKeyFilter] Mismatch usuario: JWT userId={} vs API Key userId={}. " +
                         "Acceso rechazado.", jwtUserId, apiKeyUserId);
                rechazarConCodigo(response, "API_KEY_INVALIDA",
                        "La API Key no corresponde al usuario autenticado.");
                return;
            }
            // Ambas credenciales válidas y consistentes.
            // La autenticación (userId + rol) ya está en SecurityContext desde JwtFilter.
            log.debug("[ApiKeyFilter] usuario={} autenticado con JWT + API Key.", jwtUserId);

        } catch (Exception e) {
            log.debug("[ApiKeyFilter] Error validando API Key: {}", e.getMessage());
            rechazarConCodigo(response, "API_KEY_INVALIDA", "API Key inválida o expirada.");
            return;
        }

        filterChain.doFilter(request, response);
    }

    private void rechazarConCodigo(HttpServletResponse response,
                                   String codigo, String mensaje) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        ErrorResponse error = new ErrorResponse(codigo, mensaje);
        response.getWriter().write(objectMapper.writeValueAsString(error));
    }
}
