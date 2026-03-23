package pe.extech.utilitarios.security;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * Utilidad JWT — firma HS256.
 * Claims: solo userId y planId (R5). Sin datos sensibles.
 */
@Slf4j
@Component
public class JwtUtil {

    private final SecretKey key;
    private final long expiracionMs;

    public JwtUtil(@Value("${extech.jwt.secret}") String secret,
                   @Value("${extech.jwt.expiracion-ms}") long expiracionMs) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expiracionMs = expiracionMs;
    }

    /**
     * Genera un JWT firmado con claims mínimos: userId y planId.
     * El sub lleva el email como identificador de sujeto (no como dato funcional).
     */
    public String generar(int userId, int planId, String email) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("userId", userId);
        claims.put("planId", planId);

        return Jwts.builder()
                .claims(claims)
                .subject(email)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + expiracionMs))
                .signWith(key)
                .compact();
    }

    /**
     * Valida el token y retorna los claims si es válido.
     * Lanza excepción si el token está vencido o malformado.
     */
    public Claims validar(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public int extraerUserId(Claims claims) {
        return ((Number) claims.get("userId")).intValue();
    }

    public int extraerPlanId(Claims claims) {
        return ((Number) claims.get("planId")).intValue();
    }

    public String extraerEmail(Claims claims) {
        return claims.getSubject();
    }

    public boolean esValido(String token) {
        try {
            validar(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            log.debug("JWT inválido: {}", e.getMessage());
            return false;
        }
    }
}
