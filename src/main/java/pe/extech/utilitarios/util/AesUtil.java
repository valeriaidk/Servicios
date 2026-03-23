package pe.extech.utilitarios.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Utilidad para cifrado y descifrado AES-256-CBC.
 * Usada para tokens de proveedores externos en IT_ApiExternaFuncion.Token.
 * NUNCA loguear valores descifrados.
 */
@Slf4j
@Component
public class AesUtil {

    private static final String ALGORITMO = "AES/CBC/PKCS5Padding";
    // IV fijo de 16 bytes para simplicidad v1.
    // En producción considerar IV aleatorio por cifrado.
    private static final byte[] IV = "ExtechIV16Bytes!".getBytes(StandardCharsets.UTF_8);

    private final SecretKeySpec secretKey;

    public AesUtil(@Value("${extech.aes.clave}") String clave) {
        byte[] keyBytes = clave.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length != 32) {
            throw new IllegalArgumentException(
                    "La clave AES debe tener exactamente 32 caracteres (AES-256). Actual: " + keyBytes.length);
        }
        this.secretKey = new SecretKeySpec(keyBytes, "AES");
    }

    /**
     * Cifra un valor en texto plano y retorna el resultado en Base64.
     */
    public String cifrar(String valorPlano) {
        try {
            Cipher cipher = Cipher.getInstance(ALGORITMO);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, new IvParameterSpec(IV));
            byte[] encrypted = cipher.doFinal(valorPlano.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(encrypted);
        } catch (Exception e) {
            throw new RuntimeException("Error al cifrar valor.", e);
        }
    }

    /**
     * Descifra un valor en Base64 y retorna el texto plano.
     * El valor retornado NO debe persistirse ni loguearse.
     */
    public String descifrar(String valorCifrado) {
        try {
            Cipher cipher = Cipher.getInstance(ALGORITMO);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new IvParameterSpec(IV));
            byte[] decoded = Base64.getDecoder().decode(valorCifrado);
            return new String(cipher.doFinal(decoded), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("Error al descifrar valor.", e);
        }
    }

    /**
     * Resuelve el token real a partir del valor almacenado en {@code IT_ApiExternaFuncion.Token},
     * siendo tolerante con los distintos formatos que pueden coexistir en la BD.
     *
     * <p>Algoritmo de 3 pasos — determinista, sin asumir un único formato:</p>
     *
     * <ol>
     *   <li><strong>¿Es Base64 válido?</strong><br>
     *       Si {@link Base64.Decoder#decode} lanza {@link IllegalArgumentException}, el valor
     *       contiene caracteres fuera del alfabeto Base64 (guiones, espacios, etc.).
     *       Es un token en texto plano ({@code GUID}, clave hexadecimal, etc.) → se usa tal cual.</li>
     *
     *   <li><strong>¿El largo decodificado es múltiplo de 16?</strong><br>
     *       AES-CBC + PKCS5Padding siempre produce bloques de 16 bytes exactos.
     *       Si {@code decoded.length % 16 != 0}, el valor es Base64 válido pero
     *       <em>no puede ser</em> output de {@link #cifrar} → se usa el valor almacenado tal cual
     *       (el proveedor entrega tokens que se ven como Base64 pero no están cifrados con AES).</li>
     *
     *   <li><strong>Intentar descifrado AES-256-CBC</strong><br>
     *       Si el largo es compatible (múltiplo de 16), se intenta descifrar con la clave
     *       y IV actuales.<br>
     *       - Éxito → retorna el texto plano descifrado.<br>
     *       - {@link BadPaddingException} → largo correcto pero padding no corresponde
     *         a la clave/IV actuales; el token fue almacenado antes de que se usara AES
     *         o con otra clave → se usa el valor almacenado tal cual.</li>
     * </ol>
     *
     * <p>En los tres casos de fallback se emite un WARN en los logs para que sea visible
     * que ese token pendiente debe cifrarse con {@link #cifrar} antes de pasar a producción.</p>
     *
     * @param valor valor de la columna {@code IT_ApiExternaFuncion.Token}
     * @return token en texto plano listo para insertar en el header HTTP {@code Authorization}
     */
    public String descifrarConFallback(String valor) {
        if (valor == null || valor.isBlank()) return valor;

        // ── Paso 1: ¿es Base64 válido? ────────────────────────────────────────────
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(valor);
        } catch (IllegalArgumentException e) {
            // Contiene caracteres no Base64 (guiones, espacios, etc.).
            // Es un token en formato texto plano (GUID, clave hexadecimal, etc.).
            log.warn("[AesUtil] Token[{}...] no es Base64 válido → se usa como texto plano. " +
                     "Pendiente cifrar con AesUtil antes de producción.",
                     valor.length() > 12 ? valor.substring(0, 12) : valor);
            return valor;
        }

        // ── Paso 2: AES-CBC + PKCS5 siempre produce exactamente múltiplos de 16 bytes ──
        if (decoded.length % 16 != 0) {
            // El valor es Base64 válido pero sus bytes decodificados no pueden ser
            // output de AES-CBC. El proveedor entrega un token cuya representación
            // Base64 no coincide con el tamaño de bloque AES.
            // Se usa el valor almacenado tal cual (sin decodificar).
            log.warn("[AesUtil] Token[{}...] es Base64 válido pero {} bytes decodificados " +
                     "no son múltiplo de 16 → no es output de AES-CBC. " +
                     "Se usa el valor almacenado tal cual. Pendiente cifrar antes de producción.",
                     valor.length() > 12 ? valor.substring(0, 12) : valor, decoded.length);
            return valor;
        }

        // ── Paso 3: largo compatible con AES-CBC — intentar descifrado ───────────
        try {
            Cipher cipher = Cipher.getInstance(ALGORITMO);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new IvParameterSpec(IV));
            return new String(cipher.doFinal(decoded), StandardCharsets.UTF_8);

        } catch (BadPaddingException e) {
            // Largo múltiplo de 16 pero el padding no corresponde a la clave/IV actuales.
            // El token puede ser una credencial externa cuyo largo coincide casualmente
            // con un múltiplo de 16, o fue cifrado con una clave distinta.
            // Se usa el valor almacenado tal cual.
            log.warn("[AesUtil] Token[{}...] tiene largo múltiplo de 16 pero falla AES padding " +
                     "→ no fue cifrado con la clave actual. Se usa tal cual. " +
                     "Pendiente verificar y cifrar antes de producción.",
                     valor.length() > 12 ? valor.substring(0, 12) : valor);
            return valor;

        } catch (IllegalBlockSizeException e) {
            // No debería ocurrir si decoded.length % 16 == 0, pero por robustez:
            log.warn("[AesUtil] IllegalBlockSizeException inesperado para token[{}...]. " +
                     "Se usa el valor tal cual.",
                     valor.length() > 12 ? valor.substring(0, 12) : valor);
            return valor;

        } catch (Exception e) {
            // Error de configuración del sistema (algoritmo no disponible, clave inválida,
            // IV incorrecto) — esto indica un problema de configuración, no de formato del token.
            // Se propaga para que sea visible y no se enmascare como un fallback silencioso.
            throw new RuntimeException(
                    "Error de configuración AES al intentar resolver token de proveedor externo.", e);
        }
    }
}
