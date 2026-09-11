package tp;

/**
 * HOSTIL — §1.6d, el vector de CVE-2024-23682 aplicado a nosotros.
 * El alumno declara una clase EN EL PAQUETE DEL PROFESOR con el nombre de una
 * clase de soporte, para que el test se compare contra su respuesta y no
 * contra la correcta.
 *
 * CONTENIDO    → gana el .class del profesor (tests primero en el classpath)
 * NO CONTENIDO → los tests pasan con una solucion incorrecta
 */
public class Ayuda {
    public static int esperado(int a, int b) {
        return -999;
    }
}
