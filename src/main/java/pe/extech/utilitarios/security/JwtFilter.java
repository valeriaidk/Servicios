package pe.extech.utilitarios.security;

import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Filtro JWT. Aplica a todos los endpoints protegidos:
 *   - /usuario/**  → autenticación completa por JWT
 *   - /admin/**    → autenticación completa por JWT
 *   - /servicios/** → JWT establece identidad (userId); ApiKeyFilter valida la API Key
 *
 * En /servicios/**, JWT y X-API-Key deben estar presentes y corresponder al mismo usuario.
 * JwtFilter corre primero; ApiKeyFilter corre después (ver SecurityConfig).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;

    @Value("${extech.admin.emails}")
    private String adminEmails;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        // JWT no aplica a endpoints públicos (auth y swagger).
        // Sí aplica a /servicios/**: establece la identidad del usuario
        // que ApiKeyFilter luego verifica contra el X-API-Key.
        return path.startsWith("/api/v1/auth/")
                || path.startsWith("/swagger-ui")
                || path.startsWith("/v3/api-docs");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        String header = request.getHeader("Authorization");

        if (header != null && header.startsWith("Bearer ")) {
            String token = header.substring(7);
            try {
                Claims claims = jwtUtil.validar(token);
                int userId = jwtUtil.extraerUserId(claims);
                String email = jwtUtil.extraerEmail(claims);

                // Asignar rol según lista de admins en application.properties
                boolean esAdmin = adminEmails != null
                        && List.of(adminEmails.split(","))
                              .stream()
                              .map(String::trim)
                              .anyMatch(e -> e.equalsIgnoreCase(email));

                var authority = new SimpleGrantedAuthority(esAdmin ? "ROLE_ADMIN" : "ROLE_CLIENTE");
                var auth = new UsernamePasswordAuthenticationToken(userId, null, List.of(authority));
                auth.setDetails(claims);
                SecurityContextHolder.getContext().setAuthentication(auth);

            } catch (Exception e) {
                log.debug("JWT no válido en filtro: {}", e.getMessage());
                // No interrumpir — Spring Security manejará el 401 si el endpoint lo requiere
            }
        }

        filterChain.doFilter(request, response);
    }
}
