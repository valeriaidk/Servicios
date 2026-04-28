package pe.extech.utilitarios.modules.sms.infrastructure.mapper;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import pe.extech.utilitarios.modules.sms.dto.SmsResponse;

import java.util.List;
import java.util.Map;

/**
 * Mapeador de la respuesta cruda de Infobip al DTO
 * {@link SmsResponse.SmsData} que expone el API.
 *
 * <p>
 * Extraído del antiguo {@code SmsService} para separar la orquestación
 * (UseCase) del formato específico del proveedor. Si en el futuro se
 * agrega un segundo proveedor, cada uno tendría su propio mapper.
 * </p>
 */
@Slf4j
@Component
public class InfobipSmsMapper {

    /**
     * Parsea la respuesta real de Infobip y mapea al {@link SmsResponse.SmsData}.
     * Si la respuesta viene incompleta, devuelve un {@code SmsData} con los
     * campos conocidos y el resto en {@code null} (se omiten en el JSON por
     * {@code @JsonInclude(NON_NULL)}).
     */
    @SuppressWarnings("unchecked")
    public SmsResponse.SmsData mapear(Map<String, Object> externa, String toFallback) {

        if (externa == null) {
            log.warn("[SMS] Infobip devolvió respuesta null.");
            return new SmsResponse.SmsData(null, toFallback, null, null, null, null, null);
        }

        List<Map<String, Object>> messages = (List<Map<String, Object>>) externa.get("messages");
        if (messages == null || messages.isEmpty()) {
            log.warn("[SMS] Infobip no devolvió mensajes en la respuesta.");
            return new SmsResponse.SmsData(null, toFallback, null, null, null, null, null);
        }

        Map<String, Object> msg = messages.get(0);

        String messageId = (String) msg.get("messageId");
        String toReal = msg.containsKey("to") ? (String) msg.get("to") : toFallback;
        Map<String, Object> status = (Map<String, Object>) msg.get("status");

        if (status == null) {
            log.warn("[SMS] Infobip no devolvió 'status' en el mensaje. messageId={}", messageId);
            return new SmsResponse.SmsData(messageId, toReal, null, null, null, null, null);
        }

        Integer statusId = status.get("id") instanceof Number n ? n.intValue() : null;
        String statusName = (String) status.get("name");
        Integer statusGroupId = status.get("groupId") instanceof Number ng ? ng.intValue() : null;
        String statusGroupName = (String) status.get("groupName");
        String statusDesc = (String) status.get("description");

        return new SmsResponse.SmsData(
                messageId, toReal,
                statusId, statusName,
                statusGroupId, statusGroupName, statusDesc);
    }
}
