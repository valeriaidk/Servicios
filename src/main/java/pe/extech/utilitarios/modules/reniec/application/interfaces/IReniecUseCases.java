package pe.extech.utilitarios.modules.reniec.application.interfaces;

import pe.extech.utilitarios.modules.reniec.dto.ReniecResponse;

public interface IReniecUseCases {

    ReniecResponse consultarDni(int usuarioId, String numeroDni);
}
