package pe.extech.utilitarios.sunat.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * Contrato de entrada para consulta de contribuyente por RUC.
 *
 * Ejemplo:
 * {
 *   "operation": "ENTITY.LOOKUP",
 *   "subject_code": "ENTITY|PE|PE:RUC|20100070970",
 *   "subject": { "entity_type": "ENTITY", "country": "PE" },
 *   "identifiers": [{ "scheme": "PE:RUC", "value": "20100070970" }],
 *   "options": { "sources": ["SUNAT"], "cache_ttl_sec": 86400, "force_refresh": false }
 * }
 */
public record SunatRequest(

        @NotBlank(message = "El campo 'operation' es obligatorio (ej: ENTITY.LOOKUP)")
        String operation,

        @JsonProperty("subject_code")
        String subjectCode,

        @Valid
        SubjectInfo subject,

        @NotEmpty(message = "Debe incluir al menos un identificador en 'identifiers'")
        List<@Valid Identifier> identifiers,

        Options options

) {
    public record SubjectInfo(
            @JsonProperty("entity_type") String entityType,
            String country
    ) {}

    public record Identifier(
            @NotBlank(message = "El campo 'scheme' del identificador es obligatorio (ej: PE:RUC)")
            String scheme,

            @NotBlank(message = "El campo 'value' del identificador es obligatorio")
            String value
    ) {}

    public record Options(
            List<String> sources,
            @JsonProperty("cache_ttl_sec") Integer cacheTtlSec,
            @JsonProperty("force_refresh") Boolean forceRefresh
    ) {}

    /**
     * Extrae el RUC del primer identificador con scheme "PE:RUC".
     * La validación de formato (11 dígitos, 10 o 20) la realiza ValidadorUtil en el servicio.
     */
    public String ruc() {
        if (identifiers == null) return null;
        return identifiers.stream()
                .filter(id -> "PE:RUC".equalsIgnoreCase(id.scheme()))
                .findFirst()
                .map(Identifier::value)
                .orElse(null);
    }
}
