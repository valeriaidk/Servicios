package pe.extech.utilitarios.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfigurationSource;
import pe.extech.utilitarios.exception.ErrorResponse;
import pe.extech.utilitarios.security.ApiKeyFilter;
import pe.extech.utilitarios.security.JwtFilter;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtFilter jwtFilter;
    private final ApiKeyFilter apiKeyFilter;
    private final ObjectMapper objectMapper;
    // Inyectado desde CorsConfig — garantiza que Spring Security use la misma configuración
    private final CorsConfigurationSource corsConfigurationSource;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            // CORS: debe estar ANTES de cualquier autenticación.
            // http.cors() integra CorsConfigurationSource en la cadena de Spring Security,
            // de modo que los preflights OPTIONS reciben Access-Control-Allow-Origin
            // antes de llegar a JwtFilter o ApiKeyFilter.
            .cors(cors -> cors.configurationSource(corsConfigurationSource))
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                // Preflight OPTIONS: permitir siempre sin autenticación
                .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                // Público
                .requestMatchers("/api/v1/auth/**").permitAll()
                .requestMatchers("/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**").permitAll()
                // Servicios: autenticados por ApiKeyFilter (antes de llegar aquí)
                .requestMatchers("/api/v1/servicios/**").authenticated()
                // Usuario y Admin: autenticados por JwtFilter
                .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                .requestMatchers("/api/v1/usuario/**").hasAnyRole("CLIENTE", "ADMIN")
                .anyRequest().authenticated()
            )
            // Orden de filtros (actualizado §7.5):
            //   jwtFilter → apiKeyFilter → UsernamePasswordAuthenticationFilter
            //
            // jwtFilter corre primero: procesa JWT en TODOS los endpoints protegidos,
            //   incluyendo /servicios/**. Establece userId en SecurityContext.
            // apiKeyFilter corre segundo: solo en /servicios/**. Lee userId del SecurityContext
            //   (puesto por jwtFilter) y verifica que el X-API-Key pertenezca a ese mismo usuario.
            //   Ambos headers deben estar presentes y corresponder al mismo usuario.
            .addFilterBefore(apiKeyFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(jwtFilter, ApiKeyFilter.class)
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint((request, response, authException) -> {
                    response.setStatus(401);
                    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                    response.setCharacterEncoding("UTF-8");
                    String path = request.getRequestURI();
                    String codigo = path.startsWith("/api/v1/servicios/")
                            ? "API_KEY_INVALIDA" : "JWT_INVALIDO";
                    ErrorResponse error = new ErrorResponse(codigo, "No autenticado.");
                    response.getWriter().write(objectMapper.writeValueAsString(error));
                })
                .accessDeniedHandler((request, response, accessDeniedException) -> {
                    response.setStatus(403);
                    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                    response.setCharacterEncoding("UTF-8");
                    ErrorResponse error = new ErrorResponse("ACCESO_DENEGADO",
                            "No tienes permisos para este recurso.");
                    response.getWriter().write(objectMapper.writeValueAsString(error));
                })
            );

        return http.build();
    }

    @Bean
    public AuthenticationManager authenticationManager(
            AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }
}
