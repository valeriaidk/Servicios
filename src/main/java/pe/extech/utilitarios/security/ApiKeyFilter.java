package pe.extech.utilitarios.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import pe.extech.utilitarios.exception.ErrorResponse;

import java.io.IOException;
import java.util.List;

/**
 * Filtro API Key para /servicios/**.
 *
 * ── MODO ACTUAL: API-Key-only (backend-first) ─────────────────────────────────
 * JWT no es obligatorio para consumir servicios. La API Key es suficiente.
 * Se acepta la clave en cualquiera de estas dos formas:
 *   · Header X-API-Key: <clave>
 *   · Header Authorization: Bearer <clave>   (solo si el valor NO es un JWT,
 *     es decir, si no contiene puntos — los JWT tienen formato xxx.yyy.zzz)
 *
 * Flujo:
 *   1. Extraer API Key del header (X-API-Key o Authorization Bearer sin puntos).
 *   2. Resolver userId desde BD con BCrypt (ApiKeyUtil.resolverUsuarioId).
 *   3. Establecer Authentication en SecurityContext con ese userId + ROLE_CLIENTE.
 *   4. Los controllers leen auth.getPrincipal() sin cambio alguno.
 *
 * Rechazos:
 *   - Sin API Key presente → 401 API_KEY_INVALIDA
 *   - API Key inválida/expirada → 401 API_KEY_INVALIDA
 *
 * ── MODO ORIGINAL: JWT + API Key dual (comentado, no eliminado) ───────────────
 * Para restaurar el flujo dual (R1), descomentar el bloque marcado con
 * [JWT-DUAL] y comentar el bloque marcado con [API-KEY-ONLY].
 * Ver también: SecurityConfig (orden de filtros), JwtFilter (sin cambios).
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

        // ════════════════════════════════════════════════════════════════════════
        // [JWT-DUAL] Bloque original — JWT obligatorio antes de la API Key.
        // Descomentar para restaurar el flujo dual (R1: JWT + API Key requeridos).
        // ════════════════════════════════════════════════════════════════════════
        /*
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
        */
        // ════════════════════════════════════════════════════════════════════════
        // [API-KEY-ONLY] Bloque activo — API Key como autenticación suficiente.
        // Comentar este bloque y descomentar [JWT-DUAL] para restaurar el flujo dual.
        // ════════════════════════════════════════════════════════════════════════

        // ── Paso 1: extraer API Key del header ───────────────────────────────
        // Forma A: X-API-Key: <clave>
        // Forma B: Authorization: Bearer <clave>  (solo si no es un JWT — los JWT
        //          tienen formato xxx.yyy.zzz; una API Key no contiene puntos)
        String apiKeyPlano = request.getHeader("X-API-Key");
        if (apiKeyPlano == null || apiKeyPlano.isBlank()) {
            String authHeader = request.getHeader("Authorization");
            if (authHeader != null && authHeader.startsWith("Bearer ")) {
                String candidato = authHeader.substring(7).trim();
                // Solo tratar como API Key si no tiene puntos (no es un JWT)
                if (!candidato.contains(".")) {
                    apiKeyPlano = candidato;
                }
            }
        }

        if (apiKeyPlano == null || apiKeyPlano.isBlank()) {
            rechazarConCodigo(response, "API_KEY_INVALIDA",
                    "Se requiere el header X-API-Key (o Authorization: Bearer <clave>) " +
                    "para consumir servicios.");
            return;
        }

        // ── Paso 2: validar API Key y resolver userId ─────────────────────────
        try {
            Integer apiKeyUserId = apiKeyUtil.resolverUsuarioId(apiKeyPlano);
            if (apiKeyUserId == null) {
                rechazarConCodigo(response, "API_KEY_INVALIDA",
                        "API Key inválida o expirada.");
                return;
            }

            // ── Paso 3: establecer Authentication en SecurityContext ──────────
            // Los controllers leen auth.getPrincipal() → userId sin cambio alguno.
            var auth = new UsernamePasswordAuthenticationToken(
                    apiKeyUserId, null,
                    List.of(new SimpleGrantedAuthority("ROLE_CLIENTE")));
            SecurityContextHolder.getContext().setAuthentication(auth);

            log.debug("[ApiKeyFilter] usuario={} autenticado con API Key (modo API-Key-only).",
                      apiKeyUserId);

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
