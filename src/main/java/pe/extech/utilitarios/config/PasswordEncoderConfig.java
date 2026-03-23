package pe.extech.utilitarios.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * Declara el BCryptPasswordEncoder como @Bean en una clase independiente.
 *
 * Separar este bean de SecurityConfig es obligatorio para romper la
 * dependencia circular:
 *
 *   SecurityConfig → ApiKeyFilter → ApiKeyUtil → BCryptPasswordEncoder
 *                                                       ↑
 *                                              (antes vivía aquí ↓)
 *                                              SecurityConfig
 *
 * Con esta clase el encoder no pertenece a SecurityConfig,
 * por lo que el grafo de dependencias queda sin ciclos:
 *
 *   PasswordEncoderConfig  →  BCryptPasswordEncoder
 *   ApiKeyUtil             →  BCryptPasswordEncoder  (inyección por constructor)
 *   ApiKeyFilter           →  ApiKeyUtil
 *   SecurityConfig         →  ApiKeyFilter, JwtFilter
 */
@Configuration
public class PasswordEncoderConfig {

    @Bean
    public BCryptPasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
