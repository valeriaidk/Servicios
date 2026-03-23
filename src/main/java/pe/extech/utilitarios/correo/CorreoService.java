package pe.extech.utilitarios.correo;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import pe.extech.utilitarios.correo.dto.CorreoRequest;
import pe.extech.utilitarios.correo.dto.CorreoResponse;
import pe.extech.utilitarios.domain.consumo.ConsumoRepository;
import pe.extech.utilitarios.exception.LimiteAlcanzadoException;
import pe.extech.utilitarios.exception.ServicioNoDisponibleException;
import pe.extech.utilitarios.util.EnvioBaseService;
import pe.extech.utilitarios.util.PlantillaUtil;
import pe.extech.utilitarios.util.PlanContext;
import pe.extech.utilitarios.util.ValidadorUtil;

import java.util.HashMap;
import java.util.Map;

/**
 * Servicio Correo — envío vía JavaMailSender (SMTP).
 * Extiende EnvioBaseService: plan, template, consumo = idéntico a SmsService (R4).
 * Solo enviarSmtp() difiere.
 *
 * Flujo (R2 — 1 request = 1 consumo en IT_Consumo):
 * 1. Validar límite de plan
 * 2. Validar correo destinatario localmente
 * 3. Resolver contenido y asunto (modo TEMPLATE o INLINE)
 * 4. Enviar vía SMTP
 * 5. Registrar en IT_Consumo
 * 6. Retornar respuesta enriquecida con contexto de plan
 */
@Slf4j
@Service
public class CorreoService extends EnvioBaseService {

    private final CorreoRepository correoRepository;
    private final JavaMailSender mailSender;

    public CorreoService(CorreoRepository correoRepository,
                         ConsumoRepository consumoRepository,
                         PlantillaUtil plantillaUtil,
                         ObjectMapper objectMapper,
                         JdbcTemplate jdbcTemplate,
                         JavaMailSender mailSender) {
        super(consumoRepository, plantillaUtil, objectMapper, jdbcTemplate);
        this.correoRepository = correoRepository;
        this.mailSender = mailSender;
    }

    public CorreoResponse enviar(int usuarioId, CorreoRequest request) {
        int funcionId = correoRepository.obtenerFuncionId();
        String payload = toJson(request);

        // Validar límite de plan — retorna contexto para la respuesta
        PlanContext plan = validarPlan(usuarioId, funcionId);

        // Validar correo del primer destinatario localmente
        String primaryTo = request.primaryTo();
        ValidadorUtil.validarCorreo(primaryTo);

        // Resolver contenido y asunto según modo
        String[] contenidoYAsunto = resolverContenidoCorreo(request, funcionId);
        String cuerpoHtml = contenidoYAsunto[0];
        String asunto = contenidoYAsunto[1];

        CorreoResponse respuesta;
        boolean exito = false;
        String responseJson;

        try {
            // Enviar a todos los destinatarios de la lista
            String[] recipients = request.to().toArray(String[]::new);
            String referencia = enviarSmtp(recipients, asunto, cuerpoHtml, request.bodyText());

            // consumoActual + 1: este request acaba de registrarse
            respuesta = new CorreoResponse(
                    true,
                    "OPERACION_EXITOSA",
                    "Correo enviado correctamente.",
                    usuarioId,
                    plan.plan(),
                    plan.consumoActual() + 1,
                    plan.limiteMaximo(),
                    funcionId,
                    "SMTP",
                    referencia
            );
            exito = true;
            responseJson = toJson(respuesta);
        } catch (LimiteAlcanzadoException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error enviando correo a {}: {}", request.to(), e.getMessage());
            responseJson = "{\"error\": \"" + e.getMessage() + "\"}";
            registrarConsumo(usuarioId, funcionId, payload, responseJson, false);
            throw new ServicioNoDisponibleException("SMTP-Correo");
        }

        // R2: 1 request = 1 consumo registrado en IT_Consumo
        registrarConsumo(usuarioId, funcionId, payload, responseJson, exito);
        return respuesta;
    }

    /**
     * Resuelve cuerpoHtml y asunto según el modo del request.
     * TEMPLATE: busca en IT_Template por channel+code+version.
     * INLINE: usa body_html y subject directamente.
     *
     * @return String[2] — [0] = cuerpoHtml, [1] = asunto
     */
    private String[] resolverContenidoCorreo(CorreoRequest request, int funcionId) {
        boolean isTemplate = "TEMPLATE".equalsIgnoreCase(request.mode());

        if (isTemplate) {
            if (request.template() == null) {
                throw new IllegalArgumentException(
                        "En modo TEMPLATE debe incluir el objeto 'template' con 'channel' y 'code'.");
            }
            Map<String, Object> vars = new HashMap<>(
                    request.variables() != null ? request.variables() : Map.of());
            Integer version = request.template().version();
            String canal = request.template().channel();
            String codigo = request.template().code();

            String cuerpoHtml = resolverContenido(funcionId, canal, codigo, vars, version);
            String asunto = resolverAsunto(funcionId, codigo, vars, version);

            if (cuerpoHtml == null || cuerpoHtml.isBlank()) {
                throw new IllegalArgumentException(
                        "El template '" + codigo + "' no tiene cuerpo HTML.");
            }
            return new String[]{ cuerpoHtml, asunto };
        }

        // Modo INLINE
        String cuerpoHtml = request.bodyHtml();
        String asunto = request.subject();
        if (cuerpoHtml == null || cuerpoHtml.isBlank()) {
            throw new IllegalArgumentException(
                    "En modo INLINE debe incluir el campo 'body_html'.");
        }
        return new String[]{ cuerpoHtml, asunto != null ? asunto : "Mensaje de Extech" };
    }

    private String enviarSmtp(String[] to, String asunto, String cuerpoHtml, String cuerpoTexto)
            throws Exception {
        MimeMessage message = mailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

        helper.setTo(to);
        helper.setSubject(asunto != null ? asunto : "Mensaje de Extech");
        helper.setText(cuerpoTexto != null ? cuerpoTexto : "", cuerpoHtml);

        mailSender.send(message);
        return "SMTP_" + System.currentTimeMillis();
    }
}
