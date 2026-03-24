package pe.extech.utilitarios.util;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Renderiza templates de correo y SMS.
 *
 * Los templates HTML y TXT viven como archivos en el classpath:
 *   src/main/resources/templates/correo/{codigo}.html
 *   src/main/resources/templates/correo/{codigo}.txt
 *   src/main/resources/templates/sms/{codigo}.txt
 *
 * El asunto de correo (AsuntoTemplate) sigue leyendo de IT_Template en BD,
 * lo que permite cambiarlo sin redeploy.
 *
 * La sustitución de variables usa {{variable}} en todos los templates.
 */
@Component
public class PlantillaUtil {

    /**
     * Carga el contenido de un archivo de template desde el classpath.
     *
     * @param rutaRelativa  Ruta relativa al classpath, por ejemplo
     *                      "templates/correo/otp.html" o "templates/sms/otp.txt"
     * @throws IllegalArgumentException si el archivo no existe o no puede leerse
     */
    public String cargarDesdeClasspath(String rutaRelativa) {
        ClassPathResource resource = new ClassPathResource(rutaRelativa);
        if (!resource.exists()) {
            throw new IllegalArgumentException(
                    "Template no encontrado en el classpath: '" + rutaRelativa + "'. " +
                    "Verifica que el archivo exista en src/main/resources/" + rutaRelativa + ".");
        }
        try {
            return resource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalArgumentException(
                    "No se pudo leer el template '" + rutaRelativa + "': " + e.getMessage());
        }
    }

    /**
     * Reemplaza todas las ocurrencias de {{clave}} en el template
     * con el valor correspondiente del mapa de variables.
     *
     * Variables no encontradas en el mapa quedan sin reemplazar ({{variable}} visible).
     * Valores nulos se reemplazan por cadena vacía.
     *
     * @param template   Cuerpo del template con {{variables}}
     * @param variables  Mapa de nombre → valor para sustituir
     * @return Template renderizado
     */
    public String renderizar(String template, Map<String, Object> variables) {
        if (template == null) return "";
        String resultado = template;
        for (Map.Entry<String, Object> entry : variables.entrySet()) {
            String valor = entry.getValue() != null ? String.valueOf(entry.getValue()) : "";
            resultado = resultado.replace("{{" + entry.getKey() + "}}", valor);
        }
        return resultado;
    }
}
