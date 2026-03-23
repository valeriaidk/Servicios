package pe.extech.utilitarios.correo.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;
import java.util.Map;

/**
 * Contrato de entrada para envío de correo electrónico.
 *
 * Modo TEMPLATE:
 * {
 *   "operation": "EMAIL.SEND",
 *   "mode": "TEMPLATE",
 *   "template": { "channel": "EMAIL", "code": "OTP", "version": 2 },
 *   "to": ["usuario@empresa.com"],
 *   "variables": {
 *     "code": "483921", "minutes": 5,
 *     "brand_app_name": "HCM Alertas",
 *     "brand_logo_url": "https://...",
 *     "brand_primary_color": "#16A34A",
 *     "brand_support_email": "soporte@extech.pe",
 *     "brand_footer_text": "© 2026 Extech."
 *   }
 * }
 *
 * Modo INLINE:
 * {
 *   "operation": "EMAIL.SEND",
 *   "mode": "INLINE",
 *   "to": ["usuario@empresa.com"],
 *   "subject": "Asunto del correo",
 *   "body_html": "<p>Contenido HTML</p>",
 *   "body_text": "Contenido en texto plano"
 * }
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CorreoRequest(

        @NotBlank(message = "El campo 'operation' es obligatorio (ej: EMAIL.SEND)")
        String operation,

        @NotBlank(message = "El campo 'mode' es obligatorio: TEMPLATE o INLINE")
        String mode,

        // Requerido en mode=TEMPLATE
        @Valid
        TemplateRef template,

        @NotEmpty(message = "El campo 'to' es obligatorio y debe tener al menos un destinatario")
        List<@NotBlank(message = "Los destinatarios no pueden ser vacíos") String> to,

        // Variables para sustitución en el template — solo mode=TEMPLATE
        Map<String, Object> variables,

        // Solo mode=INLINE
        String subject,

        @JsonProperty("body_html")
        String bodyHtml,

        @JsonProperty("body_text")
        String bodyText

) {
    /**
     * Referencia a un template en IT_Template.
     * channel: canal discriminador (EMAIL).
     * code: código del template (OTP, BIENVENIDA, LIMITE_ALCANZADO…).
     * version: versión exacta. Si es null, se usa la última versión activa.
     */
    public record TemplateRef(
            @NotBlank(message = "El 'channel' del template es obligatorio")
            String channel,

            @NotBlank(message = "El 'code' del template es obligatorio")
            String code,

            Integer version
    ) {}

    /** Primer destinatario de la lista (para validación y envío SMTP). */
    public String primaryTo() {
        return (to != null && !to.isEmpty()) ? to.get(0) : null;
    }
}
