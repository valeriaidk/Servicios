package pe.extech.utilitarios.modules.reniec.domain.ports;

import java.util.Map;

public interface ReniecProvider {
    Map consultar(Map<String, Object> config, String dni);

    String getProveedor(); // "DECOLECTA", "RENIEC"
}
