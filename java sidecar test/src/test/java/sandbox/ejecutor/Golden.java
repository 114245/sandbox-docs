package sandbox.ejecutor;

import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.core.command.CreateContainerCmdImpl;

/**
 * Arma el comando de create sin ningun cliente ni daemon.
 *
 * CreateContainerCmdImpl es el modelo del cuerpo del pedido: se puede instanciar suelto y
 * serializar, que es lo que deja el golden A1/A2 en pie despues de pasar a docker-java. El Exec es
 * el que ejecutaria el comando; aca no se ejecuta nada, asi que revienta si alguien lo intenta.
 */
final class Golden {
    private Golden() {}

    private static final CreateContainerCmd.Exec NUNCA_SE_EJECUTA = cmd -> {
        throw new UnsupportedOperationException("el golden no habla con ningun daemon");
    };

    static CreateContainerCmd comando(String ejecucionId, Catalogo.Perfil perfil) {
        return Spec.configurar(
                new CreateContainerCmdImpl(NUNCA_SE_EJECUTA, null, perfil.imagen()), ejecucionId, perfil);
    }

    /** Los bytes que saldrian al cable para ese id, con ese perfil. */
    static byte[] bytes(String ejecucionId, Catalogo.Perfil perfil) {
        return Spec.bytesDeCreate(comando(ejecucionId, perfil));
    }
}
