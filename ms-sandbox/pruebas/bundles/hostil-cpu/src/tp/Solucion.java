package tp;

/** HOSTIL — quema CPU sin parar. Debe disparar RLIMIT_CPU (SIGXCPU). */
public class Solucion {
    public int suma(int a, int b) {
        long x = 0;
        while (true) { x += x * 31 + 7; }
    }
}
