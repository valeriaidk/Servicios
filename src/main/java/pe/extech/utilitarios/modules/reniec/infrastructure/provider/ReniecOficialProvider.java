package pe.extech.utilitarios.modules.reniec.infrastructure.provider;

import lombok.extern.slf4j.Slf4j;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import pe.extech.utilitarios.modules.reniec.domain.ports.ReniecProvider;

import java.util.Map;

@Slf4j
@Component
@Order(2)
public class ReniecOficialProvider implements ReniecProvider {

    @Override
    public Map<String, Object> consultar(Map<String, Object> config, String dni) {
        // TODO: implementar llamada a la API oficial de RENIEC
        throw new UnsupportedOperationException("ReniecOficialProvider aún no implementado");
    }

    @Override
    public String getProveedor() {
        return "RENIEC";
    }
}