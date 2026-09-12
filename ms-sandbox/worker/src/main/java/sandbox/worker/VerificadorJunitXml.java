package sandbox.worker;

import java.io.ByteArrayInputStream;
import java.util.Map;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Suma tests, failures y errors A TRAVES DE TODOS los XML del buzon.
 *
 * La palabra "suma" no es decorativa: el bundle ok-suma deja TRES XML y DOS tienen tests="0".
 * Un verificador que mirara archivo por archivo y rechazara al primero con cero romperia el
 * camino feliz. Esta medido en 12-d16 seccion 8.3.
 */
final class VerificadorJunitXml implements VerificadorDeEvidencia {

    @Override
    public boolean soporta(String formato) {
        return "junit-xml".equals(formato);
    }

    @Override
    public Evidencia verificar(Buzon buzon) {
        int corridas = 0;
        int fallidas = 0;
        boolean huboXml = false;

        for (Map.Entry<String, byte[]> archivo : buzon.archivos().entrySet()) {
            // Todo lo que no sea .xml se ignora. En particular nota.json, que trae un campo
            // `tests` ya calculado por la capa 2: es el contador precalculado que la regla
            // madre prohibe usar como fuente del veredicto.
            if (!archivo.getKey().toLowerCase().endsWith(".xml")) continue;
            huboXml = true;

            try {
                Element raiz = raizDe(archivo.getValue());
                for (Element suite : suitesDe(raiz)) {
                    int tests     = atributo(suite, "tests");
                    int salteados = atributo(suite, "skipped");
                    // Un test salteado no ejecuto ninguna unidad de evaluacion. Una suite
                    // entera con @Disabled no puede contar como "corrio".
                    corridas += Math.max(0, tests - salteados);
                    fallidas += atributo(suite, "failures") + atributo(suite, "errors");
                }
            } catch (Exception e) {
                // Un solo XML ilegible ensucia la evidencia entera: no se puede saber cuanto
                // falta de lo que no se pudo leer. Fail-closed.
                return Evidencia.ilegible();
            }
        }

        if (!huboXml) return Evidencia.ilegible();
        return new Evidencia(corridas, fallidas, true);
    }

    /** Parseo endurecido: el XML lo escribio un proceso que corrio codigo del alumno. */
    private static Element raizDe(byte[] xml) throws Exception {
        DocumentBuilderFactory fabrica = DocumentBuilderFactory.newInstance();
        fabrica.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        fabrica.setFeature("http://xml.org/sax/features/external-general-entities", false);
        fabrica.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        fabrica.setXIncludeAware(false);
        fabrica.setExpandEntityReferences(false);
        return fabrica.newDocumentBuilder()
                      .parse(new ByteArrayInputStream(xml))
                      .getDocumentElement();
    }

    /**
     * Si la raiz es <testsuite>, es ella sola. Si es <testsuites>, son sus hijos DIRECTOS:
     * tomar todos los descendientes contaria dos veces cuando el wrapper trae agregados.
     */
    private static java.util.List<Element> suitesDe(Element raiz) {
        java.util.List<Element> suites = new java.util.ArrayList<>();
        if ("testsuite".equals(raiz.getTagName())) {
            suites.add(raiz);
            return suites;
        }
        NodeList hijos = raiz.getChildNodes();
        for (int i = 0; i < hijos.getLength(); i++) {
            Node hijo = hijos.item(i);
            if (hijo instanceof Element e && "testsuite".equals(e.getTagName())) suites.add(e);
        }
        return suites;
    }

    private static int atributo(Element suite, String nombre) {
        String valor = suite.getAttribute(nombre);
        if (valor == null || valor.isBlank()) return 0;
        try {
            return Integer.parseInt(valor.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
