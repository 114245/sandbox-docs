package sandbox.worker;

import java.util.List;
import java.util.Optional;

/**
 * El registro de verificadores. Devuelve Optional a proposito: un formato sin verificador NO
 * devuelve un verificador que no encuentra nada -- no devuelve verificador. Quien llama tiene
 * que decidir explicitamente, y la unica respuesta util es ERROR_INTERNO (12-d16 seccion 5).
 */
final class Verificadores {

    private final List<VerificadorDeEvidencia> registrados;

    private Verificadores(List<VerificadorDeEvidencia> registrados) {
        this.registrados = List.copyOf(registrados);
    }

    static Verificadores porDefecto() {
        // Hoy alcanza junit-xml. Los formatos que vengan (pmd-xml, archunit-xml) se agregan
        // aca y no tocan ni al juez ni a la imagen: 12-d16 seccion 4.
        return new Verificadores(List.of(new VerificadorJunitXml()));
    }

    Optional<VerificadorDeEvidencia> para(String formato) {
        if (formato == null || formato.isBlank()) return Optional.empty();
        return registrados.stream().filter(v -> v.soporta(formato)).findFirst();
    }
}
