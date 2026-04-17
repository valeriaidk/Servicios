package pe.extech.utilitarios.modules.reniec.domain.repository;

import java.util.Map;

public interface ReniecConfigRepository {

    Map<String, Object> resolverConfiguracion(int usuarioId);

}
