package pe.extech.utilitarios.modules.sms;

import pe.extech.utilitarios.modules.sms.dto.SmsRequest;
import pe.extech.utilitarios.modules.sms.dto.SmsResponse;

/**
 * Contrato del servicio de envío de SMS.
 *
 * Implementación actual: {@link SmsService} — envía mensajes de texto vía
 * Infobip.
 * Soporta dos modos de contenido:
 * <ul>
 * <li><b>INLINE</b>: el campo {@code message} del request se usa
 * directamente.</li>
 * <li><b>TEMPLATE</b>: se resuelve la plantilla desde {@code IT_Template}
 * (Canal=SMS)
 * mediante {@code uspTemplateObtenerAsunto} y se sustituyen las variables
 * {@code {{clave}}} con el mapa {@code variables} del request.</li>
 * </ul>
 *
 * Lógica compartida con
 * {@link pe.extech.utilitarios.modules.correo.ICorreoService}:
 * validación de plan, resolución de templates y registro de consumo en
 * {@code IT_Consumo}
 * se centralizan en {@link pe.extech.utilitarios.util.EnvioBaseService} (R4).
 *
 * Flujo interno (resumen):
 * <ol>
 * <li>{@code uspUsuarioObtenerPorId} — nombre del usuario para auditoría.</li>
 * <li>{@code uspResolverApiExternaPorUsuarioYFuncion(@UsuarioId, 'SMS_SEND')} —
 * resuelve
 * endpoint Infobip, token AES-256, template de autorización
 * ({@code App {TOKEN}})
 * y {@code ApiServicesFuncionId} desde {@code IT_ApiAsignacion}.</li>
 * <li>{@code uspPlanValidarLimiteUsuario} — corta con 429 si el límite mensual
 * está agotado.</li>
 * <li>Validación local del teléfono (formato {@code +51XXXXXXXXX}) — sin
 * consumir el plan.</li>
 * <li>Resolución de contenido según modo (TEMPLATE o INLINE).</li>
 * <li>HTTP POST a Infobip con {@code Authorization: App <token_descifrado>}.
 * Timeout: 30 s.</li>
 * <li>{@code uspConsumoRegistrar} — registra en {@code IT_Consumo} con
 * {@code EsConsulta=0},
 * siempre, tanto en éxito como en fallo (R2).</li>
 * </ol>
 *
 * Extensibilidad: para agregar un segundo proveedor (ej: Twilio, AWS SNS) basta
 * con
 * implementar esta interfaz y anotar la clase con {@code @Primary}.
 * {@link SmsController} no requiere ningún cambio.
 */
public interface ISmsService {

    /**
     * Envía un mensaje de texto al destinatario indicado en el request.
     *
     * <p>
     * El campo {@code mode} del request determina el origen del contenido:
     * {@code INLINE} usa {@code message} directamente; {@code TEMPLATE} resuelve
     * el cuerpo desde {@code IT_Template} y sustituye las variables
     * {@code {{clave}}}.
     * </p>
     *
     * <p>
     * La respuesta incluye los datos reales de Infobip: {@code messageId},
     * {@code statusId}, {@code statusName}, {@code statusGroupName} y
     * {@code statusDescription}.
     * </p>
     *
     * @param usuarioId ID del usuario autenticado, extraído del
     *                  {@code SecurityContext}
     *                  por {@link SmsController} tras validar JWT y API Key.
     * @param request   DTO con destinatario ({@code to}), modo ({@code mode}),
     *                  mensaje o referencia a template ({@code template}), y mapa
     *                  de variables para sustitución ({@code variables}).
     * @return {@link SmsResponse} con datos del envío desde Infobip y contexto
     *         del plan ({@code plan}, {@code consumoActual}, {@code limiteMaximo}).
     */
    SmsResponse enviar(int usuarioId, SmsRequest request);
}
