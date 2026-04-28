package pe.extech.utilitarios.modules.sunat.infrastructure.mapper;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pe.extech.utilitarios.modules.sunat.dto.SunatResponse;
import pe.extech.utilitarios.util.PlanContext;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pruebas unitarias del mapeo snake_case → camelCase de la respuesta
 * de Decolecta SUNAT.
 */
class DecolectaSunatMapperTest {

    private DecolectaSunatMapper mapper;

    private final PlanContext plan = new PlanContext("PROFESIONAL", 10, 1000);

    @BeforeEach
    void setUp() {
        mapper = new DecolectaSunatMapper(new ObjectMapper());
    }

    @Test
    void mapeaCamposEnRaiz() {
        Map<String, Object> externa = Map.of(
                "razon_social", "EXTECH SAC",
                "estado", "ACTIVO",
                "condicion", "HABIDO",
                "es_agente_retencion", false,
                "es_buen_contribuyente", true);

        SunatResponse resp = mapper.mapear(externa, "20100070970", 1, "USR", plan, 10);

        assertThat(resp.data().razonSocial()).isEqualTo("EXTECH SAC");
        assertThat(resp.data().estado()).isEqualTo("ACTIVO");
        assertThat(resp.data().condicion()).isEqualTo("HABIDO");
        assertThat(resp.data().esAgenteRetencion()).isFalse();
        assertThat(resp.data().esBuenContribuyente()).isTrue();
        assertThat(resp.consumoActual()).isEqualTo(11); // plan.consumoActual() + 1
    }

    @Test
    void mapeaCamposBajoNodoData() {
        Map<String, Object> externa = Map.of("data", Map.of(
                "razon_social", "EXTECH SAC",
                "estado", "ACTIVO"));

        SunatResponse resp = mapper.mapear(externa, "20100070970", 1, "USR", plan, 10);

        assertThat(resp.data().razonSocial()).isEqualTo("EXTECH SAC");
        assertThat(resp.data().estado()).isEqualTo("ACTIVO");
    }

    @Test
    void parseBoolean_aceptaStringTrueFalse() {
        Map<String, Object> externa = Map.of(
                "es_agente_retencion", "true",
                "es_buen_contribuyente", "false");

        SunatResponse resp = mapper.mapear(externa, "20100070970", 1, "USR", plan, 10);

        assertThat(resp.data().esAgenteRetencion()).isTrue();
        assertThat(resp.data().esBuenContribuyente()).isFalse();
    }

    @Test
    void parseList_aceptaListaNativaYStringJson() {
        Map<String, Object> externaConLista = Map.of("locales_anexos", List.of("L1", "L2"));
        Map<String, Object> externaConString = Map.of("locales_anexos", "[\"L1\",\"L2\"]");

        SunatResponse r1 = mapper.mapear(externaConLista, "20100070970", 1, "USR", plan, 10);
        SunatResponse r2 = mapper.mapear(externaConString, "20100070970", 1, "USR", plan, 10);

        assertThat(r1.data().localesAnexos()).hasSize(2);
        assertThat(r2.data().localesAnexos()).hasSize(2);
    }

    @Test
    void fallbackAlRucConsultado_cuandoNoHayNumeroDocumento() {
        Map<String, Object> externa = Map.of("razon_social", "EXTECH SAC");

        SunatResponse resp = mapper.mapear(externa, "20100070970", 1, "USR", plan, 10);

        assertThat(resp.data().ruc()).isEqualTo("20100070970");
    }

    @Test
    void respuestaNula_lanzaIllegalStateException() {
        assertThatThrownBy(() -> mapper.mapear(null, "20100070970", 1, "USR", plan, 10))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Decolecta-SUNAT");
    }
}
