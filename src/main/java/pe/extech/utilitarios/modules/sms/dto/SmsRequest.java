package pe.extech.utilitarios.modules.sms.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import java.util.Map;

/**
 * Contrato de entrada para envío de SMS.
 *
 * Modo TEMPLATE:
 * {
 * "mode": "TEMPLATE",
 * "template": { "channel": "SMS", "code": "OTP", "version": 1 },
 * "to": "+51999999999",
 * "variables": { "code": "1234", "minutes": 5, "brand_app_name": "Extech" }
 * }
 *
 * Modo INLINE:
 * {
 * "mode": "INLINE",
 * "to": "+51999999999",
 * "message": "Tu pedido fue confirmado."
 * }
 *
 * Campos opcionales:
 * operation — informativo (ej: "SMS.SEND"). No se usa en lógica de servicio.
 * senderId — ID de remitente para el proveedor. Si null, se usa el default
 * configurado.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SmsRequest(

                // Informativo — no se valida como obligatorio para simplificar consumo desde
                // UI.
                String operation,

                @NotBlank(message = "El campo 'mode' es obligatorio: TEMPLATE o INLINE") String mode,

                // Requerido en mode=TEMPLATE
                @Valid TemplateRef template,

                @NotBlank(message = "El destinatario 'to' es obligatorio") String to,

                // Variables para sustitución en el template — solo mode=TEMPLATE
                Map<String, Object> variables,

                // Cuerpo directo del mensaje — solo mode=INLINE
                String message,

                // ID de remitente (sender ID) para el proveedor SMS.
                // Si null, se usa extech.proveedor.infobip.sender-id de application.properties.
                String senderId

) {
        /**
         * Referencia a un template en IT_Template.
         * channel: canal discriminador (SMS).
         * code: código del template (OTP, NOTIFICACION…).
         * version: versión exacta. Si es null, se usa la última versión activa.
         */
        public record TemplateRef(
                        @NotBlank(message = "El 'channel' del template es obligatorio") String channel,

                        @NotBlank(message = "El 'code' del template es obligatorio") String code,

                        Integer version) {
        }
}
