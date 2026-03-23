package pe.extech.utilitarios.util;

import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Renderiza templates de IT_Template reemplazando {{variable}} con valores reales.
 * Usado tanto por SmsService como CorreoService (lógica idéntica).
 */
@Component
public class PlantillaUtil {

    /**
     * Reemplaza todas las ocurrencias de {{clave}} en el template
     * con el valor correspondiente del mapa de variables.
     *
     * @param template  Cuerpo del template con {{variables}}
     * @param variables Mapa de nombre-valor para sustituir
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
