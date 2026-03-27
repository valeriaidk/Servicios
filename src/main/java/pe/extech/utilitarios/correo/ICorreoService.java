package pe.extech.utilitarios.correo;

import pe.extech.utilitarios.correo.dto.CorreoRequest;
import pe.extech.utilitarios.correo.dto.CorreoResponse;

/**
 * Contrato del servicio de envío de correo electrónico.
 *
 * Implementación actual: {@link CorreoService} — envía correos vía Microsoft Graph API
 * usando el flujo OAuth2 {@code client_credentials}. Admite múltiples destinatarios y
 * soporta dos modos de contenido:
 * <ul>
 *   <li><b>INLINE</b>: {@code subject} y {@code body_html} provienen directamente del request.</li>
 *   <li><b>TEMPLATE</b>: cuerpo HTML y asunto se resuelven desde {@code IT_Template}
 *       (Canal=EMAIL) mediante {@code uspTemplateObtenerAsunto}, con sustitución de
 *       variables {@code {{clave}}}.</li>
 * </ul>
 *
 * Lógica compartida con {@link pe.extech.utilitarios.sms.ISmsService}:
 * validación de plan, resolución de templates y registro de consumo en {@code IT_Consumo}
 * se centralizan en {@link pe.extech.utilitarios.util.EnvioBaseService} (R4).
 *
 * Flujo interno (resumen):
 * <ol>
 *   <li>{@code uspUsuarioObtenerPorId} — nombre del usuario para auditoría.</li>
 *   <li>{@code uspApiServicesFuncionObtenerPorCodigo('CORREO_ENVIO')} — obtiene
 *       {@code ApiServicesFuncionId} para validación de plan y registro de consumo.</li>
 *   <li>{@code uspPlanValidarLimiteUsuario} — corta con 429 si el límite mensual está agotado.</li>
 *   <li>Validación local del correo del primer destinatario — sin consumir el plan.</li>
 *   <li>Resolución de contenido y asunto según modo (TEMPLATE o INLINE).</li>
 *   <li>{@code uspApiExternaObtenerPorCodigo('MICROSOFT_GRAPH_CORREO', 1)} — obtiene el
 *       {@code clientSecret} cifrado AES-256 desde {@code IT_ApiExternaFuncion}. Se descifra
 *       en runtime con {@link pe.extech.utilitarios.util.AesUtil}; nunca se loguea (R8).</li>
 *   <li>POST OAuth2 a {@code login.microsoftonline.com} → {@code access_token} en memoria.</li>
 *   <li>POST {@code graph.microsoft.com/v1.0/users/.../sendMail} con {@code Bearer access_token}.
 *       Graph retorna 202 Accepted sin cuerpo. Timeout: 30 s.</li>
 *   <li>{@code uspConsumoRegistrar} — registra en {@code IT_Consumo} con {@code EsConsulta=0},
 *       siempre, tanto en éxito como en fallo (R2).</li>
 * </ol>
 *
 * Extensibilidad: para agregar un segundo proveedor (ej: SendGrid directo, AWS SES) basta
 * con implementar esta interfaz y anotar la clase con {@code @Primary}.
 * {@link CorreoController} no requiere ningún cambio.
 */
public interface ICorreoService {

    /**
     * Envía un correo electrónico a los destinatarios indicados en el request.
     *
     * <p>El campo {@code mode} determina el origen del contenido: {@code INLINE} usa
     * {@code body_html} y {@code subject} directamente; {@code TEMPLATE} resuelve el HTML
     * y el asunto desde {@code IT_Template} y sustituye las variables {@code {{clave}}}.</p>
     *
     * <p>El {@code access_token} de Microsoft Graph se obtiene en runtime en cada llamada
     * y nunca se persiste ni loguea. La referencia en la respuesta sigue el formato
     * {@code GRAPH_<timestamp>} para trazabilidad.</p>
     *
     * @param usuarioId  ID del usuario autenticado, extraído del {@code SecurityContext}
     *                   por {@link CorreoController} tras validar JWT y API Key.
     * @param request    DTO con lista de destinatarios ({@code to}), modo ({@code mode}),
     *                   asunto o referencia a template ({@code template}), cuerpo HTML
     *                   ({@code body_html}) y mapa de variables ({@code variables}).
     * @return           {@link CorreoResponse} con mode, template usado, destinatarios,
     *                   referencia Graph y contexto del plan ({@code plan}, {@code consumoActual},
     *                   {@code limiteMaximo}).
     */
    CorreoResponse enviar(int usuarioId, CorreoRequest request);
}
