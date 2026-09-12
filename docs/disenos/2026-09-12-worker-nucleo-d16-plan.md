# Plan de implementación — el núcleo del worker que valida D16

> **Para ejecutores agénticos:** SUB-SKILL REQUERIDA: usar `superpowers:subagent-driven-development`
> (recomendada) o `superpowers:executing-plans` para implementar este plan tarea por tarea. Los pasos
> usan casillas (`- [ ]`) para seguimiento.

**Objetivo:** construir el núcleo del worker que decide veredictos a partir de la respuesta del
ejecutor, cerrando D16 —*dónde se verifica que hubo ejecución de verdad*— con evidencia ejecutable.

**Arquitectura:** un módulo Maven nuevo, `ms-sandbox/worker/`, con un núcleo sin I/O (verificador de
evidencia, desempaquetado del buzón y juez de veredictos) y un adaptador que habla HTTP/1.1 por
socket Unix contra el ejecutor. El fail-closed lo sostiene el sistema de tipos: no existe una
`Evidencia` que signifique *"no encontré nada y por eso está todo bien"*.

**Stack:** Java 21, Maven, JUnit 5, Jackson 2.17.2, commons-compress 1.21. Sin Spring, sin
docker-java, sin librerías de mocking.

**Spec:** [`2026-09-12-worker-nucleo-d16.md`](./2026-09-12-worker-nucleo-d16.md)

## Restricciones globales

Aplican a todas las tareas.

- **Java 21.** `maven.compiler.release` en `21`. Se usan `record`, `switch` con patrones y hilos virtuales.
- **Paquete plano `sandbox.worker`.** Sin sub-paquetes, como `sandbox.ejecutor`.
- **Todo en español:** clases, métodos, variables, comentarios, nombres de test.
- **Sin librerías de mocking.** Dobles escritos a mano, como `DaemonDePrueba` del ejecutor.
- **Nombres de test:** largos y descriptivos, anclados al criterio que fijan.
- **`*Test`** = unitario, corre siempre. **`*IT`** = necesita el ejecutor y Docker reales, **se auto-saltea si no están**.
- **Ninguna dependencia nueva** más allá de las tres listadas. En particular, **no entra docker-java**.
- **El buzón nunca se extrae a disco.** Se desempaqueta en memoria. Viene de adentro del contenedor donde corrió código del alumno.
- **`TIMEOUT_CLIENTE_MS = 75_000`**, estrictamente mayor que `TIMEOUT_EJECUCION_MS = 60_000` del ejecutor.
- **Prohibido leer contadores precalculados.** Ni `nota.json.tests`, ni `exitEval: 47` como fuente del veredicto. La fuente es el XML.

---

## Estructura de archivos

### Se crean

| Archivo | Responsabilidad |
|---|---|
| `ms-sandbox/worker/pom.xml` | Build del módulo |
| `src/main/java/sandbox/worker/Constantes.java` | Los números, en un solo lugar |
| `src/main/java/sandbox/worker/Buzon.java` | Los reportes desempaquetados, en memoria |
| `src/main/java/sandbox/worker/Evidencia.java` | `{ corridas, fallidas, legible }` |
| `src/main/java/sandbox/worker/VerificadorDeEvidencia.java` | La interfaz de D16 |
| `src/main/java/sandbox/worker/VerificadorJunitXml.java` | La implementación `junit-xml` |
| `src/main/java/sandbox/worker/Verificadores.java` | El registro por `reportFormat` |
| `src/main/java/sandbox/worker/SobreCapa1.java` | El sobre interno, `sandbox.capa1/v2` |
| `src/main/java/sandbox/worker/Desempaquetador.java` | `reportesTarGzB64` → `Buzon` |
| `src/main/java/sandbox/worker/Veredicto.java` | Los estados terminales |
| `src/main/java/sandbox/worker/Fallo.java` | `{ veredicto, consumeIntento }` |
| `src/main/java/sandbox/worker/BandaDeEvaluacion.java` | **La tabla 40-59. El archivo único que toca A3** |
| `src/main/java/sandbox/worker/Juez.java` | El mapeo de veredictos |
| `src/main/java/sandbox/worker/Sobre.java` | Los 13 campos de la respuesta del ejecutor |
| `src/main/java/sandbox/worker/Http.java` | Lectura de HTTP/1.1. **Copiada del ejecutor** |
| `src/main/java/sandbox/worker/ErrorDeEjecutor.java` | El ejecutor falló o no contestó |
| `src/main/java/sandbox/worker/EjecutorSaturado.java` | `503`, con su `Retry-After` |
| `src/main/java/sandbox/worker/ErrorDeProgramacion.java` | `400`, `411`, `413`: bug nuestro |
| `src/main/java/sandbox/worker/ClienteEjecutor.java` | El adaptador por socket Unix |
| `src/main/java/sandbox/worker/Empaquetador.java` | Directorio → tar |

### Se modifican

Ninguno. El módulo es nuevo y no toca al ejecutor.

---

## Tarea 1: El módulo y el verificador de evidencia

Es el corazón de D16 y no necesita nada más que XML en memoria.

**Archivos:**
- Crear: `ms-sandbox/worker/pom.xml`
- Crear: `ms-sandbox/worker/src/main/java/sandbox/worker/Constantes.java`
- Crear: `ms-sandbox/worker/src/main/java/sandbox/worker/Buzon.java`
- Crear: `ms-sandbox/worker/src/main/java/sandbox/worker/Evidencia.java`
- Crear: `ms-sandbox/worker/src/main/java/sandbox/worker/VerificadorDeEvidencia.java`
- Crear: `ms-sandbox/worker/src/main/java/sandbox/worker/VerificadorJunitXml.java`
- Crear: `ms-sandbox/worker/src/main/java/sandbox/worker/Verificadores.java`
- Test: `ms-sandbox/worker/src/test/java/sandbox/worker/VerificadorJunitXmlTest.java`
- Test: `ms-sandbox/worker/src/test/java/sandbox/worker/VerificadoresTest.java`

**Interfaces:**
- Consume: nada.
- Produce: `Buzon.de(Map<String,byte[]>)`, `Buzon.archivos()`;
  `Evidencia(int corridas, int fallidas, boolean legible)`;
  `VerificadorDeEvidencia.soporta(String)`, `.verificar(Buzon)`;
  `Verificadores.para(String formato)` → `Optional<VerificadorDeEvidencia>`;
  `Constantes.MAX_BUZON_BYTES`, `.TIMEOUT_CLIENTE_MS`, `.RUTA_SOCKET`.

- [ ] **Paso 1: Crear el `pom.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>

  <groupId>sandbox</groupId>
  <artifactId>worker</artifactId>
  <version>1.0.0</version>
  <packaging>jar</packaging>
  <name>worker</name>
  <description>Nucleo del worker de ms-sandbox: verificacion de evidencia y veredicto (D16)</description>

  <properties>
    <maven.compiler.release>21</maven.compiler.release>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    <jackson.version>2.17.2</jackson.version>
    <junit.version>5.10.2</junit.version>
    <commons-compress.version>1.21</commons-compress.version>
  </properties>

  <dependencies>
    <dependency>
      <groupId>com.fasterxml.jackson.core</groupId>
      <artifactId>jackson-databind</artifactId>
      <version>${jackson.version}</version>
    </dependency>
    <!--
      A diferencia del ejecutor, aca commons-compress es de PRODUCCION: desempaquetar el buzon
      de reportes es trabajo del worker, no solo de sus tests.
    -->
    <dependency>
      <groupId>org.apache.commons</groupId>
      <artifactId>commons-compress</artifactId>
      <version>${commons-compress.version}</version>
    </dependency>
    <dependency>
      <groupId>org.junit.jupiter</groupId>
      <artifactId>junit-jupiter</artifactId>
      <version>${junit.version}</version>
      <scope>test</scope>
    </dependency>
  </dependencies>

  <build>
    <finalName>worker</finalName>
    <plugins>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-compiler-plugin</artifactId>
        <version>3.13.0</version>
      </plugin>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-surefire-plugin</artifactId>
        <version>3.2.5</version>
        <configuration>
          <includes>
            <include>**/*Test.java</include>
            <!-- Los *IT necesitan el ejecutor y Docker reales; sin ellos se saltean solos. -->
            <include>**/*IT.java</include>
          </includes>
        </configuration>
      </plugin>
    </plugins>
  </build>
</project>
```

- [ ] **Paso 2: Crear `Constantes.java`**

```java
package sandbox.worker;

/** Los numeros del worker, en un solo lugar. */
final class Constantes {
    private Constantes() {}

    /**
     * Tope del cliente contra el ejecutor. DEBE ser estrictamente mayor que el
     * TIMEOUT_EJECUCION_MS del ejecutor (60 s): 04 seccion 12, la escalera de relojes.
     * Si fueran iguales, un timeout del ejecutor y uno del cliente serian indistinguibles.
     */
    static final long TIMEOUT_CLIENTE_MS = 75_000;

    /**
     * Tope de bytes DESCOMPRIMIDOS del buzon. Provisional: 12-d16 seccion 9 deja el numero
     * definitivo abierto ("hay que elegirlo y medirlo, y va en 08 seccion 4 como constante").
     *
     * Existe igual porque el buzon llega como tar.gz de adentro del contenedor: sin tope, un
     * gzip de pocos KB puede descomprimir a gigabytes. El sobre entero viaja por stdout, que el
     * ejecutor ya recorta a 1 MiB (MAX_SALIDA_BYTES), asi que 8 MiB descomprimidos es holgado
     * para reportes reales y muy lejos de cualquier cosa peligrosa.
     */
    static final int MAX_BUZON_BYTES = 8_388_608;   // 8 MiB

    /** Donde escucha el ejecutor: 08 R2.1. */
    static final String RUTA_SOCKET = "/run/ejecutor/ejecutor.sock";
}
```

- [ ] **Paso 3: Crear `Buzon.java` y `Evidencia.java`**

```java
package sandbox.worker;

import java.util.Map;

/**
 * El buzon de reportes ya desempaquetado, en memoria. Nunca se extrae a disco: viene de adentro
 * del contenedor donde corrio codigo del alumno, y un nombre de entrada con ../ no puede
 * escaparse a ningun lado si no se escribe nada.
 */
record Buzon(Map<String, byte[]> archivos) {
    static Buzon vacio() { return new Buzon(Map.of()); }

    boolean estaVacio() { return archivos.isEmpty(); }
}
```

```java
package sandbox.worker;

/**
 * Lo que un VerificadorDeEvidencia pudo leer del buzon. 12-d16 seccion 4.
 *
 * OJO con `legible`: false significa "habia algo y no se pudo leer", que NO es lo mismo que
 * `corridas == 0` ("se leyo bien y no corrio nada"). El primero es culpa nuestra y no consume
 * intento; el segundo es del alumno y si lo consume.
 */
record Evidencia(int corridas, int fallidas, boolean legible) {
    static Evidencia ilegible() { return new Evidencia(0, 0, false); }
}
```

- [ ] **Paso 4: Escribir los tests que fallan**

```java
package sandbox.worker;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VerificadorJunitXmlTest {

    private final VerificadorJunitXml verificador = new VerificadorJunitXml();

    private static Buzon buzonCon(String... paresNombreContenido) {
        Map<String, byte[]> archivos = new LinkedHashMap<>();
        for (int i = 0; i < paresNombreContenido.length; i += 2) {
            archivos.put(paresNombreContenido[i],
                         paresNombreContenido[i + 1].getBytes(StandardCharsets.UTF_8));
        }
        return new Buzon(archivos);
    }

    private static String testsuite(int tests, int fallas, int errores, int salteados) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
             + "<testsuite name=\"x\" tests=\"" + tests + "\" failures=\"" + fallas
             + "\" errors=\"" + errores + "\" skipped=\"" + salteados + "\"/>";
    }

    @Test
    void sumaLosTestsDeTodosLosXmlDelBuzon() {
        // El caso ok-suma: tres XML, dos con tests="0". Mirar archivo por archivo
        // y rechazar al primero con cero romperia el camino feliz (12-d16 seccion 8.3).
        Buzon buzon = buzonCon(
            "./TEST-a.xml", testsuite(0, 0, 0, 0),
            "./TEST-b.xml", testsuite(3, 0, 0, 0),
            "./TEST-c.xml", testsuite(0, 0, 0, 0));

        Evidencia evidencia = verificador.verificar(buzon);

        assertTrue(evidencia.legible());
        assertEquals(3, evidencia.corridas());
        assertEquals(0, evidencia.fallidas());
    }

    @Test
    void sumaFallasYErroresJuntosComoFallidas() {
        Buzon buzon = buzonCon(
            "./TEST-a.xml", testsuite(5, 2, 1, 0));

        Evidencia evidencia = verificador.verificar(buzon);

        assertEquals(5, evidencia.corridas());
        assertEquals(3, evidencia.fallidas());
    }

    @Test
    void losSalteadosNoCuentanComoCorridos() {
        // Una suite entera con @Disabled declara tests=3 y no ejecuto ninguna unidad de
        // evaluacion. Es la misma familia que el System.exit(0) que origino D6.
        Buzon buzon = buzonCon("./TEST-a.xml", testsuite(3, 0, 0, 3));

        Evidencia evidencia = verificador.verificar(buzon);

        assertTrue(evidencia.legible());
        assertEquals(0, evidencia.corridas());
    }

    @Test
    void ignoraLosArchivosQueNoSonXml() {
        // nota.json trae un campo `tests` ya calculado. Es el contador precalculado que la
        // regla madre prohibe usar como fuente: se ignora el archivo entero.
        Buzon buzon = buzonCon(
            "./nota.json", "{\"schema\":\"g5.nota/v1\",\"tests\":99}",
            "./TEST-a.xml", testsuite(2, 0, 0, 0));

        Evidencia evidencia = verificador.verificar(buzon);

        assertEquals(2, evidencia.corridas());
    }

    @Test
    void unXmlRotoDejaLaEvidenciaIlegible() {
        Buzon buzon = buzonCon(
            "./TEST-a.xml", testsuite(3, 0, 0, 0),
            "./TEST-b.xml", "<testsuite tests=\"2\"");   // sin cerrar

        Evidencia evidencia = verificador.verificar(buzon);

        assertFalse(evidencia.legible());
    }

    @Test
    void unBuzonSinNingunXmlNoEsLegible() {
        Buzon buzon = buzonCon("./nota.json", "{\"tests\":7}");

        assertFalse(verificador.verificar(buzon).legible());
    }

    @Test
    void unBuzonVacioNoEsLegible() {
        assertFalse(verificador.verificar(Buzon.vacio()).legible());
    }

    @Test
    void noResuelveEntidadesExternas() {
        // El XML lo escribio un proceso que corrio codigo del alumno. Un DOCTYPE con una
        // entidad externa no puede llegar a abrir un archivo del worker.
        String xxe = "<?xml version=\"1.0\"?>"
                   + "<!DOCTYPE a [<!ENTITY e SYSTEM \"file:///etc/passwd\">]>"
                   + "<testsuite name=\"&e;\" tests=\"1\" failures=\"0\" errors=\"0\"/>";
        Buzon buzon = buzonCon("./TEST-a.xml", xxe);

        assertFalse(verificador.verificar(buzon).legible());
    }

    @Test
    void soportaJunitXmlYNadaMas() {
        assertTrue(verificador.soporta("junit-xml"));
        assertFalse(verificador.soporta("pmd-xml"));
    }
}
```

```java
package sandbox.worker;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VerificadoresTest {

    private final Verificadores verificadores = Verificadores.porDefecto();

    @Test
    void encuentraElVerificadorDeJunitXml() {
        assertTrue(verificadores.para("junit-xml").isPresent());
    }

    @Test
    void unFormatoDesconocidoNoDevuelveVerificador() {
        // El vacio es el punto entero del fail-closed: no devuelve un verificador que no
        // encuentra nada, no devuelve verificador. 12-d16 seccion 5.
        assertTrue(verificadores.para("pmd-xml").isEmpty());
    }

    @Test
    void unFormatoNuloOVacioNoDevuelveVerificador() {
        assertTrue(verificadores.para(null).isEmpty());
        assertTrue(verificadores.para("").isEmpty());
    }
}
```

- [ ] **Paso 5: Correr los tests y verificar que fallan**

```bash
cd ms-sandbox/worker && mvn -o -B test
```

Esperado: FALLA en compilación — `VerificadorJunitXml`, `Verificadores` no existen.

- [ ] **Paso 6: Escribir la interfaz y el registro**

```java
package sandbox.worker;

/**
 * La verificacion fina de evidencia, indexada por el reportFormat de la version de perfil
 * que corrio. La interfaz es textual de 12-d16 seccion 4.
 */
interface VerificadorDeEvidencia {
    boolean soporta(String formato);
    Evidencia verificar(Buzon buzon);
}
```

```java
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
```

- [ ] **Paso 7: Escribir `VerificadorJunitXml`**

```java
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
```

- [ ] **Paso 8: Correr los tests y verificar que pasan**

```bash
cd ms-sandbox/worker && mvn -o -B test
```

Esperado: PASA. 12 tests.

- [ ] **Paso 9: Commit**

```bash
git add ms-sandbox/worker/pom.xml ms-sandbox/worker/src
git commit -m "feat(worker): verificador de evidencia junit-xml con fail-closed"
```

---

## Tarea 2: El desempaquetado del buzón

Convertir `reportesTarGzB64` del sobre de la capa 1 en un `Buzon`.

**Archivos:**
- Crear: `ms-sandbox/worker/src/main/java/sandbox/worker/SobreCapa1.java`
- Crear: `ms-sandbox/worker/src/main/java/sandbox/worker/Desempaquetador.java`
- Test: `ms-sandbox/worker/src/test/java/sandbox/worker/DesempaquetadorTest.java`

**Interfaces:**
- Consume: `Buzon`, `Constantes.MAX_BUZON_BYTES` de la Tarea 1.
- Produce: `SobreCapa1` (record con `schema`, `resultado`, `detalle`, `exitEval`,
  `procesosSobrevivientes`, `faseDeclarada`, `detalleDeclarado`, `recursos`, `reportesTarGzB64`,
  `stdoutB64`, `stderrB64`, `truncado`); `SobreCapa1.Recursos(long msEval, long cpuEvalMs)`;
  `Desempaquetador.leerSobre(String json)` → `SobreCapa1`;
  `Desempaquetador.abrirBuzon(SobreCapa1)` → `Buzon`.

- [ ] **Paso 1: Escribir el test que falla**

```java
package sandbox.worker;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.zip.GZIPOutputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DesempaquetadorTest {

    /** Arma el mismo tar.gz en base64 que produce capa1.sh con `tar -czf - -C $DIR_REPORTES .`. */
    private static String tarGzB64(String... paresNombreContenido) throws Exception {
        ByteArrayOutputStream crudo = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(crudo);
             TarArchiveOutputStream tar = new TarArchiveOutputStream(gzip)) {
            for (int i = 0; i < paresNombreContenido.length; i += 2) {
                byte[] datos = paresNombreContenido[i + 1].getBytes(StandardCharsets.UTF_8);
                TarArchiveEntry entrada = new TarArchiveEntry(paresNombreContenido[i]);
                entrada.setSize(datos.length);
                tar.putArchiveEntry(entrada);
                tar.write(datos);
                tar.closeArchiveEntry();
            }
        }
        return Base64.getEncoder().encodeToString(crudo.toByteArray());
    }

    @Test
    void leeElSobreDeLaCapa1ConTodosSusCampos() {
        String json = """
            {"schema":"sandbox.capa1/v2","resultado":"OK","detalle":"",
             "exitEval":0,"procesosSobrevivientes":0,
             "faseDeclarada":"FIN","detalleDeclarado":"la suite corrio",
             "recursos":{"msEval":4172,"cpuEvalMs":3100},
             "reportesTarGzB64":"","stdoutB64":"","stderrB64":"","truncado":false}
            """;

        SobreCapa1 sobre = Desempaquetador.leerSobre(json);

        assertEquals("sandbox.capa1/v2", sobre.schema());
        assertEquals("OK", sobre.resultado());
        assertEquals(0, sobre.exitEval());
        assertEquals(0, sobre.procesosSobrevivientes());
        assertEquals(4172, sobre.recursos().msEval());
        assertFalse(sobre.truncado());
    }

    @Test
    void desempaquetaElBuzonConservandoNombresYContenido() throws Exception {
        SobreCapa1 sobre = sobreCon(tarGzB64(
            "./TEST-a.xml", "<testsuite tests=\"1\"/>",
            "./nota.json",  "{\"tests\":1}"));

        Buzon buzon = Desempaquetador.abrirBuzon(sobre);

        assertEquals(2, buzon.archivos().size());
        assertTrue(buzon.archivos().containsKey("./TEST-a.xml"));
        assertEquals("{\"tests\":1}",
                     new String(buzon.archivos().get("./nota.json"), StandardCharsets.UTF_8));
    }

    @Test
    void unBuzonVacioDaUnBuzonVacioYNoUnError() {
        // capa1.sh deja reportesTarGzB64 en "" cuando el directorio esta vacio.
        assertTrue(Desempaquetador.abrirBuzon(sobreCon("")).estaVacio());
    }

    @Test
    void unBase64RotoTiraErrorDeSobre() {
        assertThrows(ErrorDeSobre.class, () -> Desempaquetador.abrirBuzon(sobreCon("no-es-base64!!")));
    }

    @Test
    void unBuzonQueSePasaDelTopeTiraErrorDeSobre() throws Exception {
        // Un gzip chico puede descomprimir a gigabytes. El tope corta antes de llenar memoria.
        String relleno = "x".repeat(Constantes.MAX_BUZON_BYTES + 1);
        SobreCapa1 sobre = sobreCon(tarGzB64("./gordo.xml", relleno));

        assertThrows(ErrorDeSobre.class, () -> Desempaquetador.abrirBuzon(sobre));
    }

    private static SobreCapa1 sobreCon(String reportesTarGzB64) {
        return new SobreCapa1("sandbox.capa1/v2", "OK", "", 0, 0, "", "",
                              new SobreCapa1.Recursos(0, 0), reportesTarGzB64, "", "", false);
    }
}
```

- [ ] **Paso 2: Correr el test y verificar que falla**

```bash
cd ms-sandbox/worker && mvn -o -B test -Dtest=DesempaquetadorTest
```

Esperado: FALLA en compilación — `SobreCapa1`, `Desempaquetador`, `ErrorDeSobre` no existen.

- [ ] **Paso 3: Escribir `SobreCapa1` y `ErrorDeSobre`**

```java
package sandbox.worker;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * El sobre interno, el que viaja DENTRO del campo `reporte` de la respuesta del ejecutor.
 *
 * La spec del ejecutor lo trata como opaco a proposito (08 R3.5) y no lo define. La fuente
 * real es ms-sandbox/imagenes/capa1/capa1.sh, funcion emitir().
 *
 * @JsonIgnoreProperties porque el schema puede crecer sin que el worker deje de andar; el
 * dia que el worker NECESITE un campo nuevo, se agrega aca.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
record SobreCapa1(
        String schema,
        String resultado,
        String detalle,
        /** El codigo de salida de la CAPA 2. La banda 40-59 es de G5. */
        int exitEval,
        int procesosSobrevivientes,
        /** Lo que la capa 2 dejo en $SANDBOX_STATUS. Diagnostico, NO fuente de veredicto. */
        String faseDeclarada,
        String detalleDeclarado,
        Recursos recursos,
        String reportesTarGzB64,
        String stdoutB64,
        String stderrB64,
        boolean truncado) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Recursos(long msEval, long cpuEvalMs) {}
}
```

```java
package sandbox.worker;

/** El sobre de la capa 1 no se pudo leer o el buzon no se pudo abrir. Siempre ERROR_INTERNO. */
final class ErrorDeSobre extends RuntimeException {
    ErrorDeSobre(String mensaje) { super(mensaje); }
    ErrorDeSobre(String mensaje, Throwable causa) { super(mensaje, causa); }
}
```

- [ ] **Paso 4: Escribir `Desempaquetador`**

```java
package sandbox.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;

/** Del JSON de la capa 1 al buzon en memoria. */
final class Desempaquetador {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Desempaquetador() {}

    static SobreCapa1 leerSobre(String json) {
        if (json == null || json.isBlank()) throw new ErrorDeSobre("el reporte vino vacio");
        try {
            return MAPPER.readValue(json, SobreCapa1.class);
        } catch (Exception e) {
            throw new ErrorDeSobre("el sobre de la capa 1 no se pudo parsear", e);
        }
    }

    /**
     * base64 -> gunzip -> untar, TODO en memoria. Nunca se escribe a disco: el tar viene de
     * adentro del contenedor y una entrada con ../ no puede escaparse a ningun lado si no se
     * escribe nada.
     */
    static Buzon abrirBuzon(SobreCapa1 sobre) {
        String b64 = sobre.reportesTarGzB64();
        if (b64 == null || b64.isEmpty()) return Buzon.vacio();

        byte[] targz;
        try {
            targz = Base64.getDecoder().decode(b64);
        } catch (IllegalArgumentException e) {
            throw new ErrorDeSobre("reportesTarGzB64 no es base64 valido", e);
        }

        Map<String, byte[]> archivos = new LinkedHashMap<>();
        long total = 0;
        try (TarArchiveInputStream tar =
                     new TarArchiveInputStream(new GZIPInputStream(new ByteArrayInputStream(targz)))) {
            TarArchiveEntry entrada;
            while ((entrada = tar.getNextEntry()) != null) {
                if (entrada.isDirectory()) continue;

                ByteArrayOutputStream contenido = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int leidos;
                while ((leidos = tar.read(buffer)) != -1) {
                    total += leidos;
                    if (total > Constantes.MAX_BUZON_BYTES) {
                        throw new ErrorDeSobre("el buzon supera " + Constantes.MAX_BUZON_BYTES + " bytes");
                    }
                    contenido.write(buffer, 0, leidos);
                }
                archivos.put(entrada.getName(), contenido.toByteArray());
            }
        } catch (ErrorDeSobre e) {
            throw e;
        } catch (Exception e) {
            throw new ErrorDeSobre("el buzon no se pudo desempaquetar", e);
        }
        return new Buzon(archivos);
    }
}
```

- [ ] **Paso 5: Correr los tests y verificar que pasan**

```bash
cd ms-sandbox/worker && mvn -o -B test
```

Esperado: PASA. 17 tests.

- [ ] **Paso 6: Commit**

```bash
git add ms-sandbox/worker/src
git commit -m "feat(worker): desempaquetado del sobre de la capa 1 y del buzon"
```

---

## Tarea 3: El juez y la banda de evaluación

Donde se decide el veredicto. `BandaDeEvaluacion` es **el archivo único que toca A3**.

**Archivos:**
- Crear: `ms-sandbox/worker/src/main/java/sandbox/worker/Veredicto.java`
- Crear: `ms-sandbox/worker/src/main/java/sandbox/worker/Fallo.java`
- Crear: `ms-sandbox/worker/src/main/java/sandbox/worker/BandaDeEvaluacion.java`
- Crear: `ms-sandbox/worker/src/main/java/sandbox/worker/Juez.java`
- Test: `ms-sandbox/worker/src/test/java/sandbox/worker/BandaDeEvaluacionTest.java`
- Test: `ms-sandbox/worker/src/test/java/sandbox/worker/JuezTest.java`

**Interfaces:**
- Consume: `Evidencia`, `SobreCapa1` de las Tareas 1 y 2.
- Produce: `Veredicto` (enum); `Fallo(Veredicto veredicto, boolean consumeIntento)`;
  `BandaDeEvaluacion.mapear(int exitEval)` → `Fallo`;
  `Juez.juzgar(SobreCapa1 capa1, boolean oomKilled, Optional<Evidencia> evidencia)` → `Fallo`.

- [ ] **Paso 1: Escribir los tests que fallan**

```java
package sandbox.worker;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BandaDeEvaluacionTest {

    @Test
    void mapeaLaTablaDeG5QueImplementaElPerfilJava21Junit() {
        assertEquals(Veredicto.ERROR_COMPILACION, BandaDeEvaluacion.mapear(40).veredicto());
        assertEquals(Veredicto.SUITE_INVALIDA,    BandaDeEvaluacion.mapear(41).veredicto());
        assertEquals(Veredicto.ERROR_INTERNO,     BandaDeEvaluacion.mapear(42).veredicto());
        assertEquals(Veredicto.LIMITE_MEMORIA,    BandaDeEvaluacion.mapear(43).veredicto());
        assertEquals(Veredicto.TIMEOUT,           BandaDeEvaluacion.mapear(44).veredicto());
        assertEquals(Veredicto.TIMEOUT,           BandaDeEvaluacion.mapear(45).veredicto());
        assertEquals(Veredicto.ERROR_INTERNO,     BandaDeEvaluacion.mapear(46).veredicto());
        assertEquals(Veredicto.SALIDA_ANTICIPADA, BandaDeEvaluacion.mapear(47).veredicto());
    }

    @Test
    void laSuiteRotaNoLeConsumeIntentoAlAlumno() {
        // 41 es "no compila la suite de pruebas": la suite es de la catedra.
        assertFalse(BandaDeEvaluacion.mapear(41).consumeIntento());
    }

    @Test
    void elRelojDePlataformaNoLeConsumeIntentoAlAlumno() {
        // 42 es javac pasandose del presupuesto de compilacion, que es NUESTRO.
        assertFalse(BandaDeEvaluacion.mapear(42).consumeIntento());
    }

    @Test
    void unCodigoSinAsignarDeLaBandaEsErrorInternoYNoConsumeIntento() {
        // Fail-closed: un codigo que no sabemos leer no puede aprobar ni castigar al alumno.
        for (int codigo = 48; codigo <= 59; codigo++) {
            Fallo fallo = BandaDeEvaluacion.mapear(codigo);
            assertEquals(Veredicto.ERROR_INTERNO, fallo.veredicto(), "codigo " + codigo);
            assertFalse(fallo.consumeIntento(), "codigo " + codigo);
        }
    }

    @Test
    void unCodigoFueraDeLaBandaEsErrorInterno() {
        assertEquals(Veredicto.ERROR_INTERNO, BandaDeEvaluacion.mapear(39).veredicto());
        assertEquals(Veredicto.ERROR_INTERNO, BandaDeEvaluacion.mapear(60).veredicto());
    }
}
```

```java
package sandbox.worker;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JuezTest {

    private static SobreCapa1 capa1(String resultado, int exitEval) {
        return new SobreCapa1("sandbox.capa1/v2", resultado, "", exitEval, 0, "", "",
                              new SobreCapa1.Recursos(0, 0), "", "", "", false);
    }

    private static Optional<Evidencia> evidencia(int corridas, int fallidas) {
        return Optional.of(new Evidencia(corridas, fallidas, true));
    }

    // ---- la regla madre ----

    @Test
    void noHayExitoSinPruebasCorridasLeidasDelXml() {
        Fallo fallo = Juez.juzgar(capa1("OK", 0), false, evidencia(0, 0));

        assertEquals(Veredicto.SALIDA_ANTICIPADA, fallo.veredicto());
        assertTrue(fallo.consumeIntento());
    }

    @Test
    void unaSuiteQueCorrioYPasoEsExitoYNoConsumeIntento() {
        Fallo fallo = Juez.juzgar(capa1("OK", 0), false, evidencia(3, 0));

        assertEquals(Veredicto.EXITO, fallo.veredicto());
        assertFalse(fallo.consumeIntento());
    }

    @Test
    void unaSuiteConFallasEsTestsFallidos() {
        Fallo fallo = Juez.juzgar(capa1("OK", 0), false, evidencia(3, 1));

        assertEquals(Veredicto.TESTS_FALLIDOS, fallo.veredicto());
        assertTrue(fallo.consumeIntento());
    }

    // ---- fail-closed ----

    @Test
    void sinVerificadorParaElFormatoNoHayExitoAunqueLaCapa1DigaOk() {
        Fallo fallo = Juez.juzgar(capa1("OK", 0), false, Optional.empty());

        assertEquals(Veredicto.ERROR_INTERNO, fallo.veredicto());
        assertFalse(fallo.consumeIntento());
    }

    @Test
    void conEvidenciaIlegibleNoHayExitoAunqueLaCapa1DigaOk() {
        Fallo fallo = Juez.juzgar(capa1("OK", 0), false, Optional.of(Evidencia.ilegible()));

        assertEquals(Veredicto.ERROR_INTERNO, fallo.veredicto());
        assertFalse(fallo.consumeIntento());
    }

    // ---- el enum real de la capa 1 ----

    @Test
    void elBackstopDeParedDeLaCapa1EsTimeout() {
        assertEquals(Veredicto.TIMEOUT, Juez.juzgar(capa1("TIMEOUT_PARED", 0), false, Optional.empty()).veredicto());
    }

    @Test
    void losProcesosSobrevivientesDanVeredictoNoConfiable() {
        assertEquals(Veredicto.VEREDICTO_NO_CONFIABLE,
                     Juez.juzgar(capa1("VEREDICTO_NO_CONFIABLE", 0), false, evidencia(3, 0)).veredicto());
    }

    @Test
    void elBuzonVacioDeLaCapa1EsErrorInterno() {
        assertEquals(Veredicto.ERROR_INTERNO, Juez.juzgar(capa1("SIN_REPORTE", 0), false, Optional.empty()).veredicto());
    }

    @Test
    void unSigkillConOomKilledEsLimiteDeMemoria() {
        assertEquals(Veredicto.LIMITE_MEMORIA,
                     Juez.juzgar(capa1("MUERTO_POR_SENAL", 0), true, Optional.empty()).veredicto());
    }

    @Test
    void unSigkillSinOomKilledEsErrorInterno() {
        assertEquals(Veredicto.ERROR_INTERNO,
                     Juez.juzgar(capa1("MUERTO_POR_SENAL", 0), false, Optional.empty()).veredicto());
    }

    @Test
    void unResultadoDesconocidoDeLaCapa1EsErrorInterno() {
        assertEquals(Veredicto.ERROR_INTERNO,
                     Juez.juzgar(capa1("ALGO_QUE_NO_EXISTE", 0), false, evidencia(3, 0)).veredicto());
    }

    // ---- la banda 40-59 ----

    @Test
    void detenidoPorEvaluacionDelegaEnLaBanda() {
        assertEquals(Veredicto.ERROR_COMPILACION,
                     Juez.juzgar(capa1("DETENIDO_POR_EVALUACION", 40), false, Optional.empty()).veredicto());
    }

    // ---- el orden de guardas ----

    @Test
    void unTimeoutConCeroPruebasEsTimeoutYNoSalidaAnticipada() {
        // La guarda de orden de 12-d16 seccion 2: si esto se rompe, una entrega que quemo la
        // CPU se le reporta al alumno como si hubiera salido temprano a proposito.
        Fallo porBackstop = Juez.juzgar(capa1("TIMEOUT_PARED", 0), false, evidencia(0, 0));
        Fallo porRelojCpu = Juez.juzgar(capa1("DETENIDO_POR_EVALUACION", 44), false, evidencia(0, 0));

        assertEquals(Veredicto.TIMEOUT, porBackstop.veredicto());
        assertEquals(Veredicto.TIMEOUT, porRelojCpu.veredicto());
    }

    @Test
    void elConteoDeLosXmlLeGanaAlExitEval47DeLaCapa2() {
        // 47 es la capa 2 declarando tests=0 por su cuenta. Es la Opcion B que 12-d16
        // seccion 3 descarta: el vigilado vigilandose. Si el conteo real dice que corrieron
        // pruebas, manda el conteo.
        Fallo fallo = Juez.juzgar(capa1("DETENIDO_POR_EVALUACION", 47), false, evidencia(3, 0));

        assertEquals(Veredicto.EXITO, fallo.veredicto());
    }
}
```

- [ ] **Paso 2: Correr los tests y verificar que fallan**

```bash
cd ms-sandbox/worker && mvn -o -B test
```

Esperado: FALLA en compilación — `Veredicto`, `Fallo`, `BandaDeEvaluacion`, `Juez` no existen.

- [ ] **Paso 3: Escribir `Veredicto` y `Fallo`**

```java
package sandbox.worker;

/** Los estados terminales. 03 seccion 5.1, "los estados terminales". */
enum Veredicto {
    EXITO,
    TESTS_FALLIDOS,
    ERROR_COMPILACION,
    TIMEOUT,
    LIMITE_MEMORIA,
    SALIDA_ANTICIPADA,
    SUITE_INVALIDA,
    VEREDICTO_NO_CONFIABLE,
    ERROR_INTERNO
}
```

```java
package sandbox.worker;

/**
 * Un veredicto y si le cuesta un intento al alumno. Van juntos a proposito: el par es lo que
 * se le devuelve a T05, y separarlos deja que alguien mande un ERROR_INTERNO que consume vida.
 */
record Fallo(Veredicto veredicto, boolean consumeIntento) {

    /** Nuestro problema, no del alumno. Nunca consume intento (12-d16 seccion 5). */
    static Fallo interno() { return new Fallo(Veredicto.ERROR_INTERNO, false); }

    static Fallo delAlumno(Veredicto veredicto) { return new Fallo(veredicto, true); }
}
```

- [ ] **Paso 4: Escribir `BandaDeEvaluacion`**

```java
package sandbox.worker;

/**
 * La banda 40-59: los codigos con los que la CAPA 2 se frena a proposito.
 *
 * ============================ EL ARCHIVO QUE TOCA A3 ============================
 * Esta tabla es PROVISIONAL. D19 ("bandas de codigos de salida") le reserva la banda 40-59 a
 * G5, y A3 -- "que etiquetas de fase y que codigos 40-59 definen" -- todavia no fue
 * contestada. Lo que hay aca es la tabla que implementa NUESTRO perfil de referencia,
 * ms-sandbox/perfiles/java21-junit.sh, que escribimos nosotros.
 *
 * Cuando llegue la tabla de A3, se cambia ESTE archivo y nada mas. Por eso el juez delega en
 * vez de tener el switch adentro, y por eso la tabla es global y no por perfil: D19 propone
 * una sola banda para toda la plataforma.
 * ===============================================================================
 */
final class BandaDeEvaluacion {

    private static final int MINIMO = 40;
    private static final int MAXIMO = 59;

    private BandaDeEvaluacion() {}

    static boolean contiene(int exitEval) {
        return exitEval >= MINIMO && exitEval <= MAXIMO;
    }

    static Fallo mapear(int exitEval) {
        return switch (exitEval) {
            case 40 -> Fallo.delAlumno(Veredicto.ERROR_COMPILACION);   // no compila la solucion
            case 41 -> new Fallo(Veredicto.SUITE_INVALIDA, false);     // la suite es de la catedra
            case 42 -> Fallo.interno();                                // reloj de plataforma de javac
            case 43 -> Fallo.delAlumno(Veredicto.LIMITE_MEMORIA);      // la JVM se quedo sin memoria
            case 44 -> Fallo.delAlumno(Veredicto.TIMEOUT);             // reloj de CPU del alumno
            case 45 -> Fallo.delAlumno(Veredicto.TIMEOUT);             // backstop de pared de la suite
            case 46 -> Fallo.interno();                                // la JVM no escribio reporte
            case 47 -> Fallo.delAlumno(Veredicto.SALIDA_ANTICIPADA);   // reporte con tests=0
            // Un codigo de la banda que todavia no tiene significado no puede aprobar ni
            // castigar: fail-closed hasta que A3 lo defina.
            default -> Fallo.interno();
        };
    }
}
```

- [ ] **Paso 5: Escribir `Juez`**

```java
package sandbox.worker;

import java.util.Optional;

/**
 * El mapeo de veredictos. Funcion pura: no sabe de sockets, ni de tar, ni de formatos.
 *
 * OJO con el orden del switch: ES la guarda de orden de 12-d16 seccion 2. Los timeouts se
 * resuelven por `resultado` y por la banda, asi que llegan antes de que la rama OK pueda mirar
 * `corridas == 0`. Si alguien aplana esto a una cadena de ifs sobre `corridas`, la propiedad se
 * pierde en silencio y una entrega que quemo la CPU pasa a reportarse como salida anticipada.
 */
final class Juez {

    private Juez() {}

    static Fallo juzgar(SobreCapa1 capa1, boolean oomKilled, Optional<Evidencia> evidencia) {
        return switch (capa1.resultado()) {
            case "OK"                      -> juzgarCorridaCompleta(evidencia);
            case "DETENIDO_POR_EVALUACION" -> juzgarFrenoDeLaCapa2(capa1, evidencia);
            case "TIMEOUT_PARED"           -> Fallo.delAlumno(Veredicto.TIMEOUT);
            case "VEREDICTO_NO_CONFIABLE"  -> new Fallo(Veredicto.VEREDICTO_NO_CONFIABLE, false);
            case "MUERTO_POR_SENAL"        -> oomKilled
                                                  ? Fallo.delAlumno(Veredicto.LIMITE_MEMORIA)
                                                  : Fallo.interno();
            case "SIN_REPORTE",
                 "BUNDLE_INVALIDO",
                 "EVALUACION_ANOMALA"      -> Fallo.interno();
            // Un resultado que no conocemos es un despliegue desalineado, no una entrega mala.
            default                        -> Fallo.interno();
        };
    }

    /**
     * La capa 2 llego al final. Aca y solo aca se mira la evidencia.
     *
     * El Optional vacio es el fail-closed de 12-d16 seccion 5: sin verificador para el formato
     * declarado, no hay EXITO posible. Que sea un Optional y no una Evidencia con corridas=0 es
     * lo que evita que el camino natural del codigo sea el peor.
     */
    private static Fallo juzgarCorridaCompleta(Optional<Evidencia> evidencia) {
        if (evidencia.isEmpty() || !evidencia.get().legible()) return Fallo.interno();

        Evidencia e = evidencia.get();
        if (e.corridas() == 0) return Fallo.delAlumno(Veredicto.SALIDA_ANTICIPADA);
        if (e.fallidas() > 0)  return Fallo.delAlumno(Veredicto.TESTS_FALLIDOS);
        return new Fallo(Veredicto.EXITO, false);
    }

    /**
     * La capa 2 se freno a proposito con un codigo de su banda.
     *
     * Excepcion deliberada al mapeo de la banda: si la capa 2 declaro 47 ("reporte con
     * tests=0") pero el conteo real de los XML dice que SI corrieron pruebas, manda el conteo.
     * El 47 es la capa 2 hablando de si misma, y 12-d16 seccion 3 descarta esa fuente al
     * rechazar la Opcion B: pedirle al vigilado que se vigile.
     */
    private static Fallo juzgarFrenoDeLaCapa2(SobreCapa1 capa1, Optional<Evidencia> evidencia) {
        if (capa1.exitEval() == 47
                && evidencia.isPresent()
                && evidencia.get().legible()
                && evidencia.get().corridas() > 0) {
            return juzgarCorridaCompleta(evidencia);
        }
        if (!BandaDeEvaluacion.contiene(capa1.exitEval())) return Fallo.interno();
        return BandaDeEvaluacion.mapear(capa1.exitEval());
    }
}
```

- [ ] **Paso 6: Correr los tests y verificar que pasan**

```bash
cd ms-sandbox/worker && mvn -o -B test
```

Esperado: PASA. 36 tests.

- [ ] **Paso 7: Commit**

```bash
git add ms-sandbox/worker/src
git commit -m "feat(worker): juez de veredictos y banda de evaluacion 40-59"
```

---

## Tarea 4: El cliente del ejecutor

El adaptador que habla HTTP/1.1 por socket Unix.

**Archivos:**
- Crear: `ms-sandbox/worker/src/main/java/sandbox/worker/Http.java` (copiada del ejecutor)
- Crear: `ms-sandbox/worker/src/main/java/sandbox/worker/Sobre.java`
- Crear: `ms-sandbox/worker/src/main/java/sandbox/worker/ErrorDeEjecutor.java`
- Crear: `ms-sandbox/worker/src/main/java/sandbox/worker/EjecutorSaturado.java`
- Crear: `ms-sandbox/worker/src/main/java/sandbox/worker/ErrorDeProgramacion.java`
- Crear: `ms-sandbox/worker/src/main/java/sandbox/worker/ClienteEjecutor.java`
- Test: `ms-sandbox/worker/src/test/java/sandbox/worker/EjecutorDePrueba.java` (doble a mano)
- Test: `ms-sandbox/worker/src/test/java/sandbox/worker/ClienteEjecutorTest.java`

**Interfaces:**
- Consume: `Constantes.TIMEOUT_CLIENTE_MS`, `.RUTA_SOCKET` de la Tarea 1.
- Produce: `Sobre` (record de 13 campos); `ClienteEjecutor(Path rutaSocket)`;
  `ClienteEjecutor.ejecutar(String ejecucionId, String perfil, byte[] tar)` → `Sobre`;
  `ErrorDeEjecutor`, `EjecutorSaturado(long segundosDeEspera)`, `ErrorDeProgramacion`.

- [ ] **Paso 1: Copiar `Http.java` del ejecutor**

```bash
cp ms-sandbox/ejecutor/src/main/java/sandbox/ejecutor/Http.java \
   ms-sandbox/worker/src/main/java/sandbox/worker/Http.java
```

Después, en el archivo copiado: cambiar `package sandbox.ejecutor;` por `package sandbox.worker;`
y reemplazar el javadoc de la clase por este, que deja escrito de dónde salió:

```java
/**
 * Lo minimo de HTTP/1.1 que hace falta para leer una respuesta del ejecutor.
 *
 * COPIA de sandbox.ejecutor.Http. Se copia y no se comparte a proposito: depender del jar del
 * ejecutor le arrastraria docker-java entero al worker (21 MB, la deuda anotada en 08 R11.4), y
 * extraer un modulo comun por 90 lineas no se paga con dos consumidores. Si aparece un tercero,
 * esta copia es el argumento para extraerlo.
 *
 * java.net.http.HttpClient no sirve: no habla sockets Unix (JDK-8377806).
 */
```

- [ ] **Paso 2: Escribir el doble del ejecutor y el test que falla**

```java
package sandbox.worker;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * Un ejecutor de mentira sobre un socket Unix. Escrito a mano, como DaemonDePrueba del
 * ejecutor: el proyecto no usa librerias de mocking.
 */
final class EjecutorDePrueba implements AutoCloseable {

    private final ServerSocketChannel escucha;
    private final Thread hilo;
    private final String respuestaCruda;
    private final long demoraMs;
    private volatile byte[] tarRecibido;
    private volatile String cabezaRecibida;

    private EjecutorDePrueba(Path ruta, String respuestaCruda, long demoraMs) throws Exception {
        this.respuestaCruda = respuestaCruda;
        this.demoraMs = demoraMs;
        this.escucha = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        this.escucha.bind(UnixDomainSocketAddress.of(ruta.toString()));
        this.hilo = Thread.ofVirtual().start(this::atender);
    }

    static EjecutorDePrueba queResponde(Path ruta, String respuestaCruda) throws Exception {
        return new EjecutorDePrueba(ruta, respuestaCruda, 0);
    }

    static EjecutorDePrueba queNuncaResponde(Path ruta) throws Exception {
        return new EjecutorDePrueba(ruta, "", Long.MAX_VALUE);
    }

    /** Respuesta 200 con un sobre minimo cuyo campo `reporte` es lo que se le pase. */
    static String respuesta200(String reporteJson) {
        String cuerpo = """
            {"ejecucionId":"3f2b","resultado":"COMPLETADA","exitCode":0,"oomKilled":false,\
            "duracionMs":4172,"stdout":"","stderr":"","reporte":%s,"reporteAusente":false,\
            "salidaTruncada":false,"perfilId":"java21-junit","perfilVersion":4,"perfilHash":"9f86"}\
            """.formatted(reporteJson);
        return "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: "
                + cuerpo.getBytes(StandardCharsets.UTF_8).length + "\r\n\r\n" + cuerpo;
    }

    static String respuestaSinCuerpo(int codigo, String razon, String headersExtra) {
        return "HTTP/1.1 " + codigo + " " + razon + "\r\n" + headersExtra + "Content-Length: 0\r\n\r\n";
    }

    byte[] tarRecibido()      { return tarRecibido; }
    String cabezaRecibida()   { return cabezaRecibida; }

    private void atender() {
        try (SocketChannel conexion = escucha.accept()) {
            InputStream in = Channels.newInputStream(conexion);
            Http.Cabeza cabeza = Http.leerCabeza(in);
            cabezaRecibida = cabeza.linea() + "|" + cabeza.header("x-perfil")
                           + "|" + cabeza.header("x-ejecucion-id");
            int largo = Integer.parseInt(cabeza.header("content-length"));
            tarRecibido = Http.leerExacto(in, largo);

            if (demoraMs > 0) Thread.sleep(demoraMs);

            OutputStream out = Channels.newOutputStream(conexion);
            out.write(respuestaCruda.getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (Exception e) {
            // El socket cerrado por el cliente es parte de las pruebas: no es un fallo.
        }
    }

    @Override
    public void close() throws Exception {
        hilo.interrupt();
        escucha.close();
    }
}
```

```java
package sandbox.worker;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ClienteEjecutorTest {

    private static final byte[] TAR = "tar-de-mentira".getBytes(StandardCharsets.UTF_8);
    private static final String ID  = "3f2b0000-0000-4000-8000-000000000000";

    private static Path socketEn(Path dir) {
        return dir.resolve("e.sock");
    }

    @Test
    void mandaLosTresHeadersYElTarEnElCuerpo(@TempDir Path dir) throws Exception {
        Path socket = socketEn(dir);
        try (EjecutorDePrueba ejecutor =
                     EjecutorDePrueba.queResponde(socket, EjecutorDePrueba.respuesta200("null"))) {

            new ClienteEjecutor(socket).ejecutar(ID, "java21-junit@4", TAR);

            assertEquals("POST /ejecutar HTTP/1.1|java21-junit@4|" + ID, ejecutor.cabezaRecibida());
            assertArrayEquals(TAR, ejecutor.tarRecibido());
        }
    }

    @Test
    void devuelveElSobreConSusTreceCampos(@TempDir Path dir) throws Exception {
        Path socket = socketEn(dir);
        try (EjecutorDePrueba ignorado =
                     EjecutorDePrueba.queResponde(socket, EjecutorDePrueba.respuesta200("\"{}\""))) {

            Sobre sobre = new ClienteEjecutor(socket).ejecutar(ID, "java21-junit@4", TAR);

            assertEquals("COMPLETADA", sobre.resultado());
            assertEquals(0, sobre.exitCode());
            assertFalse(sobre.oomKilled());
            assertFalse(sobre.reporteAusente());
            assertEquals("java21-junit", sobre.perfilId());
            assertEquals(4, sobre.perfilVersion());
        }
    }

    @Test
    void un503EsEjecutorSaturadoYLlevaElRetryAfter(@TempDir Path dir) throws Exception {
        Path socket = socketEn(dir);
        try (EjecutorDePrueba ignorado = EjecutorDePrueba.queResponde(
                socket, EjecutorDePrueba.respuestaSinCuerpo(503, "Service Unavailable", "Retry-After: 7\r\n"))) {

            EjecutorSaturado e = assertThrows(EjecutorSaturado.class,
                    () -> new ClienteEjecutor(socket).ejecutar(ID, "java21-junit@4", TAR));

            assertEquals(7, e.segundosDeEspera());
        }
    }

    @Test
    void un502EsErrorDeEjecutor(@TempDir Path dir) throws Exception {
        Path socket = socketEn(dir);
        try (EjecutorDePrueba ignorado = EjecutorDePrueba.queResponde(
                socket, EjecutorDePrueba.respuestaSinCuerpo(502, "Bad Gateway", ""))) {

            assertThrows(ErrorDeEjecutor.class,
                    () -> new ClienteEjecutor(socket).ejecutar(ID, "java21-junit@4", TAR));
        }
    }

    @Test
    void un400EsErrorDeProgramacionPorqueElBugEsNuestro(@TempDir Path dir) throws Exception {
        Path socket = socketEn(dir);
        try (EjecutorDePrueba ignorado = EjecutorDePrueba.queResponde(
                socket, EjecutorDePrueba.respuestaSinCuerpo(400, "Bad Request", ""))) {

            assertThrows(ErrorDeProgramacion.class,
                    () -> new ClienteEjecutor(socket).ejecutar(ID, "java21-junit@4", TAR));
        }
    }

    @Test
    void unSocketQueNoExisteEsErrorDeEjecutor(@TempDir Path dir) {
        assertThrows(ErrorDeEjecutor.class,
                () -> new ClienteEjecutor(dir.resolve("no-esta.sock")).ejecutar(ID, "x@1", TAR));
    }

    @Test
    void siElEjecutorNoRespondeAntesDelTopeEsErrorDeEjecutor(@TempDir Path dir) throws Exception {
        // No se espera 75 s: se construye el cliente con un tope corto. Lo que se prueba es que
        // el tope EXISTE y despierta al lector, no su valor de produccion.
        Path socket = socketEn(dir);
        try (EjecutorDePrueba ignorado = EjecutorDePrueba.queNuncaResponde(socket)) {

            ClienteEjecutor cliente = new ClienteEjecutor(socket, 300);

            assertThrows(ErrorDeEjecutor.class, () -> cliente.ejecutar(ID, "java21-junit@4", TAR));
        }
    }

    @Test
    void elTopeDeProduccionEsMayorQueElDelEjecutor() {
        // 04 seccion 12, la escalera de relojes: 60 s ejecutor < 75 s cliente. Nunca iguales.
        assertTrue(Constantes.TIMEOUT_CLIENTE_MS > 60_000);
    }
}
```

- [ ] **Paso 3: Correr los tests y verificar que fallan**

```bash
cd ms-sandbox/worker && mvn -o -B test -Dtest=ClienteEjecutorTest
```

Esperado: FALLA en compilación — `ClienteEjecutor`, `Sobre` y las excepciones no existen.

> **Si estos tests fallan en Windows con un error de dirección del socket**, no es el código: la
> ruta de un socket Unix tiene un tope duro de ~108 bytes, y `@TempDir` puede producir rutas más
> largas que eso. Se arregla apuntando el test a un directorio corto —`System.getProperty("java.io.tmpdir")`
> con un nombre de dos o tres caracteres— en vez de `@TempDir`. No cambiar el cliente por esto.

- [ ] **Paso 4: Escribir `Sobre` y las tres excepciones**

```java
package sandbox.worker;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * La respuesta del ejecutor: 08 seccion 3.1, "la respuesta". Trece campos, y el orden importa
 * del lado del ejecutor (R3.7), no del nuestro.
 *
 * `reporte` es opaco para el ejecutor (R3.5) y es donde viaja el SobreCapa1.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
record Sobre(
        String ejecucionId,
        String resultado,
        Integer exitCode,
        boolean oomKilled,
        long duracionMs,
        String stdout,
        String stderr,
        String reporte,
        boolean reporteAusente,
        boolean salidaTruncada,
        String perfilId,
        Integer perfilVersion,
        String perfilHash) {}
```

```java
package sandbox.worker;

/** El ejecutor fallo, no contesto, o contesto algo que no se pudo leer. Siempre ERROR_INTERNO. */
class ErrorDeEjecutor extends RuntimeException {
    ErrorDeEjecutor(String mensaje) { super(mensaje); }
    ErrorDeEjecutor(String mensaje, Throwable causa) { super(mensaje, causa); }
}
```

```java
package sandbox.worker;

/**
 * 503: la cola del ejecutor esta llena. NO hay veredicto y NO es un intento del alumno.
 * Cuando exista la cola, esto se reintenta con backoff; por ahora se propaga.
 */
final class EjecutorSaturado extends RuntimeException {

    private final long segundosDeEspera;

    EjecutorSaturado(long segundosDeEspera) {
        super("el ejecutor esta saturado; reintentar en " + segundosDeEspera + " s");
        this.segundosDeEspera = segundosDeEspera;
    }

    long segundosDeEspera() { return segundosDeEspera; }
}
```

```java
package sandbox.worker;

/**
 * 400, 411 y 413: el ejecutor rechazo el pedido por como estaba armado. Es un bug NUESTRO, no
 * una entrega mala. Cuando exista la cola, esto va derecho a la DLQ: reintentarlo produciria
 * exactamente el mismo rechazo.
 */
final class ErrorDeProgramacion extends RuntimeException {
    ErrorDeProgramacion(String mensaje) { super(mensaje); }
}
```

- [ ] **Paso 5: Escribir `ClienteEjecutor`**

```java
package sandbox.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** POST /ejecutar por socket Unix. 08 seccion 2 y 3.1. */
final class ClienteEjecutor {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_RESPUESTA_BYTES = 16_777_216;   // 16 MiB

    private final Path rutaSocket;
    private final long topeMs;

    ClienteEjecutor(Path rutaSocket) {
        this(rutaSocket, Constantes.TIMEOUT_CLIENTE_MS);
    }

    /** El tope explicito existe para los tests: produccion siempre usa TIMEOUT_CLIENTE_MS. */
    ClienteEjecutor(Path rutaSocket, long topeMs) {
        this.rutaSocket = rutaSocket;
        this.topeMs = topeMs;
    }

    Sobre ejecutar(String ejecucionId, String perfil, byte[] tar) {
        try (SocketChannel canal = SocketChannel.open(StandardProtocolFamily.UNIX)) {
            canal.connect(UnixDomainSocketAddress.of(rutaSocket.toString()));
            escribirPedido(Channels.newOutputStream(canal), ejecucionId, perfil, tar);
            return leerConTope(canal);
        } catch (EjecutorSaturado | ErrorDeProgramacion | ErrorDeEjecutor e) {
            throw e;
        } catch (Exception e) {
            throw new ErrorDeEjecutor("no se pudo hablar con el ejecutor en " + rutaSocket, e);
        }
    }

    private void escribirPedido(OutputStream salida, String ejecucionId, String perfil, byte[] tar)
            throws Exception {
        String cabeza = "POST /ejecutar HTTP/1.1\r\n"
                      + "Host: localhost\r\n"                       // R5.5
                      + "Content-Type: application/octet-stream\r\n"
                      + "X-Ejecucion-Id: " + ejecucionId + "\r\n"
                      + "X-Perfil: " + perfil + "\r\n"
                      + "Content-Length: " + tar.length + "\r\n"    // sin chunked: 08 seccion 3.1
                      + "\r\n";
        salida.write(cabeza.getBytes(StandardCharsets.ISO_8859_1));
        salida.write(tar);
        salida.flush();
    }

    /**
     * Un SocketChannel bloqueante NO tiene SO_TIMEOUT y Channels.newInputStream se cuelga para
     * siempre. La lectura va en un hilo virtual y, si vence el tope, se cierra el canal desde
     * aca: el lector despierta con AsynchronousCloseException.
     *
     * El executor se apaga con shutdownNow() en el finally y NO con try-with-resources: close()
     * de un executor de tareas virtuales ESPERA a que terminen, y la tarea que queremos cortar
     * es justamente la que esta colgada.
     */
    private Sobre leerConTope(SocketChannel canal) {
        InputStream entrada = Channels.newInputStream(canal);
        ExecutorService hilos = Executors.newVirtualThreadPerTaskExecutor();
        try {
            Future<Sobre> tarea = hilos.submit(() -> leerRespuesta(entrada));
            try {
                return tarea.get(topeMs, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                cerrarCallado(canal);
                throw new ErrorDeEjecutor("el ejecutor no respondio en " + topeMs + " ms", e);
            } catch (Exception e) {
                Throwable causa = e.getCause() != null ? e.getCause() : e;
                if (causa instanceof EjecutorSaturado s) throw s;
                if (causa instanceof ErrorDeProgramacion p) throw p;
                if (causa instanceof ErrorDeEjecutor d) throw d;
                throw new ErrorDeEjecutor("fallo la lectura de la respuesta", causa);
            }
        } finally {
            hilos.shutdownNow();
        }
    }

    private Sobre leerRespuesta(InputStream entrada) throws Exception {
        Http.Cabeza cabeza = Http.leerCabeza(entrada);
        int codigo = Http.codigo(cabeza.linea());
        byte[] cuerpo = Http.leerCuerpo(entrada, cabeza, MAX_RESPUESTA_BYTES);

        switch (codigo) {
            case 200 -> { /* sigue abajo */ }
            case 503 -> throw new EjecutorSaturado(segundosDe(cabeza.header("retry-after")));
            case 400, 411, 413 -> throw new ErrorDeProgramacion(
                    "el ejecutor rechazo el pedido con " + codigo + ": es un bug del worker");
            // 422 es un perfil que no esta en el catalogo: despliegue desalineado, no bug del
            // pedido. 502 es ERROR_DAEMON. Los dos son ERROR_INTERNO.
            default -> throw new ErrorDeEjecutor("el ejecutor respondio " + codigo);
        }

        try {
            return MAPPER.readValue(cuerpo, Sobre.class);
        } catch (Exception e) {
            throw new ErrorDeEjecutor("la respuesta del ejecutor no se pudo parsear", e);
        }
    }

    private static long segundosDe(String retryAfter) {
        if (retryAfter == null || retryAfter.isBlank()) return 1;
        try {
            return Math.max(1, Long.parseLong(retryAfter.trim()));
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    private static void cerrarCallado(SocketChannel canal) {
        try {
            canal.close();
        } catch (Exception e) {
            // Cerrar para despertar al lector; que falle el cierre no cambia el resultado.
        }
    }
}
```

- [ ] **Paso 6: Correr los tests y verificar que pasan**

```bash
cd ms-sandbox/worker && mvn -o -B test
```

Esperado: PASA. 44 tests.

- [ ] **Paso 7: Commit**

```bash
git add ms-sandbox/worker/src
git commit -m "feat(worker): cliente del ejecutor por socket unix con tope de lectura"
```

---

## Tarea 5: El empaquetador y `D16IT`

La prueba que cierra D16, contra el ejecutor y la imagen reales.

**Archivos:**
- Crear: `ms-sandbox/worker/src/main/java/sandbox/worker/Empaquetador.java`
- Test: `ms-sandbox/worker/src/test/java/sandbox/worker/EmpaquetadorTest.java`
- Test: `ms-sandbox/worker/src/test/java/sandbox/worker/D16IT.java`

**Interfaces:**
- Consume: todo lo anterior.
- Produce: `Empaquetador.desdeDirectorio(Path raiz)` → `byte[]` (tar sin comprimir).

- [ ] **Paso 1: Escribir el test del empaquetador**

```java
package sandbox.worker;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class EmpaquetadorTest {

    @Test
    void empaquetaConRutasRelativasYSeparadorPosix(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("src/com"));
        Files.writeString(dir.resolve("src/com/A.java"), "class A {}");
        Files.createDirectories(dir.resolve("test"));
        Files.writeString(dir.resolve("test/ATest.java"), "class ATest {}");

        List<String> nombres = nombresDe(Empaquetador.desdeDirectorio(dir));

        // Separador POSIX siempre: el tar lo abre la capa 1 adentro de un contenedor Linux,
        // aunque el worker corra en Windows.
        assertTrue(nombres.contains("src/com/A.java"), nombres.toString());
        assertTrue(nombres.contains("test/ATest.java"), nombres.toString());
        assertTrue(nombres.stream().noneMatch(n -> n.contains("\\")));
    }

    @Test
    void conservaElContenidoDeCadaArchivo(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("run.sh"), "#!/bin/sh\necho hola\n");

        byte[] tar = Empaquetador.desdeDirectorio(dir);

        assertEquals("#!/bin/sh\necho hola\n", contenidoDe(tar, "run.sh"));
    }

    private static List<String> nombresDe(byte[] tar) throws Exception {
        List<String> nombres = new ArrayList<>();
        try (TarArchiveInputStream in = new TarArchiveInputStream(new ByteArrayInputStream(tar))) {
            TarArchiveEntry e;
            while ((e = in.getNextEntry()) != null) if (!e.isDirectory()) nombres.add(e.getName());
        }
        return nombres;
    }

    private static String contenidoDe(byte[] tar, String nombre) throws Exception {
        try (TarArchiveInputStream in = new TarArchiveInputStream(new ByteArrayInputStream(tar))) {
            TarArchiveEntry e;
            while ((e = in.getNextEntry()) != null) {
                if (e.getName().equals(nombre)) return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
        return null;
    }
}
```

- [ ] **Paso 2: Correr y verificar que falla**

```bash
cd ms-sandbox/worker && mvn -o -B test -Dtest=EmpaquetadorTest
```

Esperado: FALLA en compilación — `Empaquetador` no existe.

- [ ] **Paso 3: Escribir `Empaquetador`**

```java
package sandbox.worker;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;

/**
 * Directorio -> tar SIN comprimir, que es lo que el ejecutor espera en el cuerpo del POST
 * (08 seccion 3.1, Content-Type: application/octet-stream).
 *
 * PROVISIONAL en un punto: no valida las rutas del bundle. Esa validacion es RUTA_INVALIDA,
 * que 07 seccion 4 marca como inexistente ("no existe hoy y hace falta") y ubica del lado de
 * la API, no del worker. Cuando exista, este empaquetador recibe rutas ya validadas.
 */
final class Empaquetador {

    private Empaquetador() {}

    static byte[] desdeDirectorio(Path raiz) {
        ByteArrayOutputStream crudo = new ByteArrayOutputStream();
        try (TarArchiveOutputStream tar = new TarArchiveOutputStream(crudo)) {
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
            List<Path> archivos;
            try (Stream<Path> recorrido = Files.walk(raiz)) {
                archivos = recorrido.filter(Files::isRegularFile).sorted().toList();
            }
            for (Path archivo : archivos) {
                // Separador POSIX siempre: el tar lo abre la capa 1 adentro de un contenedor
                // Linux, aunque el worker corra en Windows.
                String nombre = raiz.relativize(archivo).toString().replace('\\', '/');
                byte[] datos = Files.readAllBytes(archivo);
                TarArchiveEntry entrada = new TarArchiveEntry(nombre);
                entrada.setSize(datos.length);
                tar.putArchiveEntry(entrada);
                tar.write(datos);
                tar.closeArchiveEntry();
            }
        } catch (Exception e) {
            throw new ErrorDeProgramacion("no se pudo empaquetar el bundle de " + raiz + ": " + e);
        }
        return crudo.toByteArray();
    }
}
```

- [ ] **Paso 4: Escribir `D16IT`**

```java
package sandbox.worker;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * LA PRUEBA QUE CIERRA D16.
 *
 * Son los pasos 4 y 5 de la validacion de 12-d16 seccion 8.4, que quedaron sin correr con una
 * razon explicita: "no existen todavia ni los perfiles ni el worker". Los perfiles ya existen;
 * con esta rodaja existe el worker.
 *
 * Necesita el ejecutor levantado contra Docker real. Sin socket, se saltea.
 */
class D16IT {

    private static final String PERFIL  = "java21-junit@4";
    private static final Path   BUNDLES = Path.of("..", "pruebas", "bundles");

    private static Path socket() {
        String desdeElEntorno = System.getenv("SANDBOX_EJECUTOR_SOCKET");
        return Path.of(desdeElEntorno != null ? desdeElEntorno : Constantes.RUTA_SOCKET);
    }

    private static Fallo veredictoDe(String bundle) {
        byte[] tar = Empaquetador.desdeDirectorio(BUNDLES.resolve(bundle));
        Sobre sobre = new ClienteEjecutor(socket()).ejecutar(UUID.randomUUID().toString(), PERFIL, tar);

        assertFalse(sobre.reporteAusente(), "el ejecutor no encontro bloque de reporte");

        SobreCapa1 capa1  = Desempaquetador.leerSobre(sobre.reporte());
        Buzon      buzon  = Desempaquetador.abrirBuzon(capa1);

        Optional<Evidencia> evidencia = Verificadores.porDefecto()
                .para("junit-xml")
                .map(v -> v.verificar(buzon));

        return Juez.juzgar(capa1, sobre.oomKilled(), evidencia);
    }

    @Test
    void unaEntregaQueLlamaSystemExitCeroNoPuedeAprobar() {
        assumeTrue(Files.exists(socket()), "sin socket del ejecutor: se saltea");

        Fallo fallo = veredictoDe("hostil-exit0");

        assertNotEquals(Veredicto.EXITO, fallo.veredicto(),
                "hostil-exit0 aprobo: la guarda de D16 no esta sosteniendo nada");
        assertEquals(Veredicto.SALIDA_ANTICIPADA, fallo.veredicto());
    }

    @Test
    void elCaminoFelizDeTresXmlAprueba() {
        assumeTrue(Files.exists(socket()), "sin socket del ejecutor: se saltea");

        // ok-suma deja TRES XML y DOS con tests="0". Si el verificador mirara archivo por
        // archivo en vez de sumar, esto daria SALIDA_ANTICIPADA y el camino feliz estaria roto.
        Fallo fallo = veredictoDe("ok-suma");

        assertEquals(Veredicto.EXITO, fallo.veredicto());
        assertFalse(fallo.consumeIntento());
    }

    @Test
    void unFormatoSinVerificadorNoPuedeAprobarElCaminoFeliz() {
        assumeTrue(Files.exists(socket()), "sin socket del ejecutor: se saltea");

        // El fail-closed contra el sobre REAL del camino feliz: cambiar solo el formato
        // declarado tiene que tumbar el EXITO. Es la propiedad de 12-d16 seccion 5.
        byte[] tar = Empaquetador.desdeDirectorio(BUNDLES.resolve("ok-suma"));
        Sobre sobre = new ClienteEjecutor(socket()).ejecutar(UUID.randomUUID().toString(), PERFIL, tar);
        SobreCapa1 capa1 = Desempaquetador.leerSobre(sobre.reporte());

        Optional<Evidencia> sinVerificador = Verificadores.porDefecto()
                .para("pmd-xml")
                .map(v -> v.verificar(Desempaquetador.abrirBuzon(capa1)));

        Fallo fallo = Juez.juzgar(capa1, sobre.oomKilled(), sinVerificador);

        assertEquals(Veredicto.ERROR_INTERNO, fallo.veredicto());
        assertFalse(fallo.consumeIntento());
    }
}
```

- [ ] **Paso 5: Correr la suite entera sin el ejecutor levantado**

```bash
cd ms-sandbox/worker && mvn -o -B test
```

Esperado: PASA. Los tres tests de `D16IT` aparecen como **saltados**, no como fallidos.

- [ ] **Paso 6: Levantar el ejecutor y correr `D16IT` de verdad**

```bash
# En otra terminal, con Docker corriendo y la imagen sandbox-runner:2.0.0-capa1 construida:
cd ms-sandbox/ejecutor && mvn -o -B -DskipTests package && java -jar target/ejecutor.jar

# Y entonces:
cd ms-sandbox/worker && mvn -o -B test -Dtest=D16IT
```

Esperado: PASA, **con los tres tests ejecutados y ninguno saltado**.

> **Este es el criterio de aceptación de la rodaja.** Si `unaEntregaQueLlamaSystemExitCeroNoPuedeAprobar`
> pasa con el ejecutor real, D16 dejó de ser una decisión escrita y pasó a ser una propiedad
> sostenida por código.

- [ ] **Paso 7: Commit**

```bash
git add ms-sandbox/worker/src
git commit -m "feat(worker): empaquetador de bundles y prueba de integracion de D16"
```

---

## Estado final

**Ejecutado el 12 de septiembre de 2026.** Siete commits, `cbe937f..8b18499`. El criterio de
aceptación **se cumplió**: `D16IT` corrió contra el ejecutor y la imagen reales (Docker 29.7.2,
`sandbox-runner:2.0.0-capa1`) con los tres tests **ejecutados y ninguno salteado**. Suite entera:
**72 tests, 0 fallas, 0 salteados.**

`hostil-exit0` no aprobó —dio `SALIDA_ANTICIPADA`—, `ok-suma` aprobó sin consumir intento, y
declarar un formato sin verificador tumbó el `EXITO`. **D16 dejó de ser una decisión escrita y pasó
a ser una propiedad sostenida por código.**

La revisión final de la rama encontró que el plan había recortado el Paso 1 del §5 del diseño sin
declararlo: la composición de un sobre a un veredicto sólo existía dentro de `D16IT`. Se cerró con
`Nucleo.evaluar(Sobre)`, que además es lo que hace que los tres tests de aceptación ejerciten
código de producción en vez de una cadena armada en el test.

> **Un hallazgo ajeno a este plan, encontrado al correr la aceptación.** `mvn package` del módulo
> `ejecutor` **falla**: el `maven-shade-plugin` tropieza con los jars firmados de BouncyCastle
> (*"Invalid signature file digest for Manifest main attributes"*), y el jar ya compilado tira el
> mismo error en tiempo de ejecución. **El fat jar del ejecutor nunca funcionó**; no se notó porque
> siempre se verificó por tests y nunca por el artefacto empaquetado. Para correr `D16IT` se
> levantó el ejecutor por classpath. No se tocó nada del ejecutor. El arreglo conocido es un filtro
> en el shade que excluya `META-INF/*.SF`, `*.DSA` y `*.RSA`.

## Después del plan

Cosas que quedan anotadas y **no** son parte de esta rodaja:

0. **El `reportFormat` no se lee del perfil.** `Nucleo` usa `junit-xml` como constante. El camino de producción del fail-closed por formato sólo se ejercita desde tests. Se cierra con el catálogo de perfiles.

1. **Escribir el esquema de `sandbox.capa1/v2` en `08` §5** como contrato formal. Hoy el único lugar donde está definido es `capa1.sh`.
2. **Corregir `04` §6**, que lista valores del sobre que la capa 1 ya no emite.
3. **Elegir y medir el tope del buzón**, y moverlo a `08` §4 como constante. Hoy `Constantes.MAX_BUZON_BYTES` lo fija provisionalmente en 8 MiB.
4. **Cerrar D7**: si `VEREDICTO_NO_CONFIABLE` consume vida. Hoy está en `false` en `Juez`.
5. **Cerrar A3** y revisar `BandaDeEvaluacion`, que es el archivo único preparado para eso.
