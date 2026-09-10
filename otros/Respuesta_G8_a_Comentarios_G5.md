# Respuesta a los comentarios del G5

**De:** Grupo 8 — Tema 06, Sandbox / Runtime
**Para:** Grupo 5 — Tema 05, Desafíos Prácticos
**Sobre:** sus comentarios a `Respuesta_G8_a_Propuesta_V4.md`
**Fecha:** 9 de septiembre de 2026

Van los diez puntos en el mismo orden en que los mandaron. Cuatro son "sí, y ya está construido
así"; tres necesitan una decisión de ustedes; dos son cosas que sólo ustedes pueden darnos.

---

## 1. Procesos sobrevivientes: no frenar el flujo

**De acuerdo, y es casi exactamente lo que ya hace la capa 1.** Barrer procesos nunca fue un
`abort`: la secuencia es *barrer → empaquetar el buzón → emitir el sobre → salir*. El sobre sale
completo —el tar de `/work/reports`, `stdout`, `stderr`, los tiempos— **y además** lleva el campo
`procesosSobrevivientes` con el número. O sea que los resultados de las pruebas les llegan igual.

Lo que cambia con su pedido es el **significado**, no el mecanismo, y lo aceptamos: dejamos de
tratarlo como fracaso terminal de nuestro lado y se los pasamos como bandera.

Tres precisiones para que quede sin ambigüedad:

- **Nuestros códigos de salida no cruzan hacia ustedes.** La banda `20–31` es plomería entre la capa
  1 y nuestro worker; lo que les llega es el campo `status` del evento, que para este caso vale
  `VEREDICTO_NO_CONFIABLE` y va acompañado del reporte. (Lo que sí les propagamos tal cual es **su**
  banda `40–59`, en el campo `exitCode`: ese número es de ustedes y es el que mapea contra su tabla.)
- **Ya existe un código de esa banda para este caso exacto: el `30`.** El `21` está libre, así que
  si prefieren ese número lo renumeramos sin costo — pero el `30` ya está implementado y probado, y
  como el byte no cruza hacia ustedes, la elección es cosmética. Nuestra recomendación es dejar el
  `30` y que ustedes flagueen por `status == "VEREDICTO_NO_CONFIABLE"`.
- **La advertencia que nos toca hacerles:** el reporte que reciben en ese caso *puede ser el
  falsificado*. El vector es justamente que un proceso vivo reescriba el XML después de que el
  script terminó. Reciben los resultados, sí, pero no son evidencia confiable de nada — sirven para
  investigar el intento, no para calcular una nota. Si igual se usan para la nota, la trampa paga.

---

## 2. "Que lo rechacen ustedes antes de armarlo"

**Antes que nada, una corrección nuestra: esa frase estaba mal redactada y los puede haber
confundido con razón. Ustedes no arman ningún tar.** El tar lo armamos nosotros. Pedimos disculpas
por el rodeo; va la cadena real, que es más simple.

### Quién toca qué

```
1. el alumno sube sus archivos                             → ustedes
2. POST /ejecuciones  { archivos: [ {ruta, contenidoB64} ] } → ustedes nos llaman
3. nuestra API valida cada `ruta` y responde 202 o 400      → nosotros
4. el worker arma el tar en memoria (src/, test/)           → nosotros
5. la capa 1 lo extrae adentro del contenedor               → nosotros
```

Lo que nos mandan es **una lista de archivos en JSON**, cada uno con su `ruta` y su contenido en
base64. El tar aparece recién en el paso 4 y es enteramente nuestro.

Eso tiene una consecuencia que conviene decir explícitamente, porque simplifica el pedido: **como el
tar lo construimos nosotros a partir de una lista plana de archivos, ahí adentro no puede haber un
symlink ni un hardlink.** No hay forma de que nos manden uno. Toda esa mitad del párrafo del
documento anterior no les aplica: es una guarda nuestra, sobre nuestro propio armador y nuestro
propio extractor, y la mantenemos por disciplina, no porque ustedes puedan disparársela.

### Entonces qué les pedimos

Lo que sí es entrada hostil de punta a punta es **el string `ruta` de cada archivo**. Ese lo escribe
—o lo influye— el alumno, viaja intacto por su sistema y por el nuestro, y termina siendo el nombre
de un archivo que se escribe en disco adentro del contenedor.

El pedido, entonces: **validen la `ruta` de cada archivo cuando el alumno lo sube, y si no cumple,
corten ahí con un `400`.**

| Regla | Rechazar si |
|---|---|
| Ruta relativa | empieza con `/`, o con `C:\`, o con una barra invertida |
| Sin escapes | contiene un segmento `..` |
| Extensión esperada | no termina en `.java` (o lo que corresponda al perfil) |
| Sin duplicados | la misma `ruta` aparece dos veces en el bundle |
| Topes | más de N archivos, más de X MB en total, `ruta` de más de 255 bytes |

Son exactamente las mismas que aplica nuestra API en el paso 3, con el mismo `400`.

### Por qué las queremos de los dos lados

- **La de ustedes es la única que puede explicar el error.** Ustedes tienen al alumno, la entrega y
  la sesión HTTP abierta: pueden decirle *"el archivo `x` tiene una ruta inválida"* en el momento y
  que lo corrija. Cuando lo rechazamos nosotros, lo mejor que podemos devolver es un `400` genérico
  sobre un `bundle` que para nosotros no tiene dueño ni contexto.
- **La nuestra es la que tiene que aguantar el ataque.** No dependemos de que ustedes la hagan, y no
  les pedimos que dependan de la nuestra. Un bug en cualquiera de las dos no alcanza para nada.
- **Es la validación más barata del sistema.** Es comparar strings, antes de que exista un
  contenedor, una cola o un mensaje.

> **Un aviso honesto sobre el estado de esto de nuestro lado:** la validación de `ruta` en nuestra
> API **todavía no está construida**. Lo descubrimos corriendo el prototipo con dos entregas
> maliciosas: las dos quedaron contenidas, pero **por accidente** — a una la frenó GNU tar al
> desempaquetar y la otra terminó como error de compilación. Confiar en que el desempaquetador
> rechace lo que la API dejó pasar es tener la validación en el lugar equivocado, así que la estamos
> agregando. Se los contamos para que quede claro que el pedido no es "háganlo ustedes porque
> nosotros no": es que va en los dos lados.

Un `..` en una ruta controlada por el atacante no es un caso hipotético: es la familia de bug que
produjo los dos CVEs 10.0 de Judge0.

### Un pariente cercano, ya que estamos

Hay un segundo string del bundle que es entrada hostil por el mismo motivo y que **sí necesita algo
de ustedes**: el `package` que declara el código del alumno. Validar la ruta no alcanza — un archivo
en `src/tp/Solucion.java` puede declarar `package tp.tests;` adentro y sombrear una clase de soporte
del profesor, aprobando sin resolver nada. La guarda la aplicamos nosotros, pero **necesitamos que
nos digan cuál es el paquete reservado de los tests** de ese desafío. Sin ese dato no hay contra qué
comparar. Lo dejamos anotado acá y lo formalizamos donde prefieran.

---

## 3. `$SANDBOX_STATUS/fase` y `$SANDBOX_STATUS/detalle`: cómo funciona

### Qué problema resuelve

Cuando el script de ustedes se frena antes de llegar al final, lo único que nos deja es un número
—el código de salida— y ese número es **un byte**. Con `41` podemos mostrarle al alumno *"no
compila"*, pero no *qué* no compila.

Estos dos archivos son la manera de agregarle texto a ese número: **`fase` dice en qué etapa se
frenó y `detalle` dice por qué**. Los dos son opcionales; si no los escriben, todo lo demás sigue
funcionando igual.

### Qué tipo de archivo son

**Texto plano, una línea, sin formato.** No es JSON, no tiene esquema, no tiene extensión. Son dos
rutas fijas dentro de la carpeta `/work/status` (la que les llega como `$SANDBOX_STATUS`), y se
escriben con un `echo` común:

| Archivo | Contenido | Lo que hacemos con él |
|---|---|---|
| `$SANDBOX_STATUS/fase` | una etiqueta corta: `COMPILACION`, `PRUEBAS`, `ANALISIS`… | leemos los primeros **128 bytes** |
| `$SANDBOX_STATUS/detalle` | una línea de diagnóstico en castellano | leemos los primeros **512 bytes** |

Las etiquetas de `fase` las definen ustedes. Nosotros las copiamos tal cual, no las validamos contra
ninguna lista y no cambiamos nada de nuestro comportamiento según lo que digan. Si más adelante
quieren que sea un enum cerrado, se declara en el perfil.

### Cómo se usan, del lado de ustedes

```sh
echo "COMPILACION" > "$SANDBOX_STATUS/fase"
javac -d "$SANDBOX_TMP/clases" src/*.java 2>"$SANDBOX_TMP/javac.err" || {
  echo "no compila: $(head -n 1 "$SANDBOX_TMP/javac.err")" > "$SANDBOX_STATUS/detalle"
  exit 41
}

echo "PRUEBAS" > "$SANDBOX_STATUS/fase"
java -jar "$SANDBOX_LIBS/junit.jar" … || true    # las pruebas que fallan NO son un error del script

echo "ANALISIS" > "$SANDBOX_STATUS/fase"
…
exit 0
```

Dos reglas de uso:

- **Se pisan.** Si el script escribe `COMPILACION` y después `PRUEBAS`, al final vale `PRUEBAS`. Está
  pensado justo para eso: el script va dejando escrito hasta dónde llegó, y lo último que alcanzó a
  escribir es lo que vale.
- **Escríbanlos siempre antes de arrancar la etapa, no después.** El valor sirve cuando el script
  *no* llega al final; si lo escriben al terminar bien, nunca van a ver la etapa que falló.

### Cómo se usan, del lado nuestro

Los leemos **una sola vez, después de que su script terminó** (paso 7 de la capa 1). Nunca los
miramos mientras corre y nunca reaccionamos a ellos en vivo. Si no existen, quedan como cadena
vacía y no pasa nada.

### Cómo vuelven — esto era su pregunta

**Vuelven como dos campos del evento, no como archivos y no adentro del tar de reportes.**

El recorrido completo:

```
run.sh escribe /work/status/fase = "COMPILACION"
        │
        ▼
capa 1 los lee al terminar y los mete en el sobre
        como  faseDeclarada / detalleDeclarado
        │
        ▼
el worker los saca del sobre y los pone en el resumen
        │
        ▼
a ustedes les llegan en EXECUTION_COMPLETED (y también en el GET)
```

```jsonc
{
  "type": "EXECUTION_COMPLETED",
  "payload": {
    "executionId": "…",
    "status": "DETENIDO_POR_EVALUACION",
    "exitCode": 41,
    "resumen": {
      "faseDeclarada":   "COMPILACION",
      "detalleDeclarado": "no compila: Main.java:14: error: ';' expected"
    }
  }
}
```

O sea que ustedes reciben **tres cosas que se complementan** y arman el mensaje al alumno con las
que tengan:

| Qué reciben | De dónde sale | Qué tan confiable es |
|---|---|---|
| `exitCode` 41 | lo produce el sistema operativo | **siempre llega** |
| `faseDeclarada` = `COMPILACION` | lo escribió su script | llega si alcanzó a escribirlo |
| `detalleDeclarado` = el error de `javac` | lo escribió su script | ídem |

Y por eso son dos canales y no uno: **si el script se muere de golpe** —lo mata el kernel por
consumo de CPU, tiene un error de sintaxis, se queda sin memoria— **no alcanzó a escribir nada, pero
el número igual nos llega**. El archivo es el canal rico pero frágil; el número, el pobre pero
indestructible.

### La advertencia que nos toca hacerles

`/work/status` es escribible por el usuario del sandbox, así que **el código del alumno también puede
escribir ahí**. Son un canal **declarativo, no autoritativo**: sirven para el mensaje que ve el
alumno, nunca para decidir la nota ni para clasificar la ejecución. El veredicto sale del reporte,
siempre.

---

## 4. §2.2 — a qué archivos van `stdout` y `stderr`, y si vuelve el `err`

- **Van a `$SANDBOX_TMP`, no a `reports`:** `/work/tmp/fase.out` y `/work/tmp/fase.err`.
- **Sí, están separados** en dos archivos distintos, desde el primer byte.
- **Sí, les devolvemos el `err`.** Ya vuelven los dos, cada uno en su propio campo del sobre
  (`stdoutB64` / `stderrB64`), en base64 y truncados a un tope de bytes, con una bandera `truncado`
  que dice si se cortó algo. No se mezclan nunca.

El motivo de que vayan a `tmp` y no a `reports` es que `reports` es de ustedes: si dejáramos ahí
nuestros archivos, el tar que empaquetamos "sin mirarlo" tendría adentro cosas que ustedes no
pusieron. `tmp` no vuelve como carpeta, pero estos dos archivos sí vuelven, por el canal del sobre.

Nota de uso: `$SANDBOX_TMP/fase.out` y `.err` son legibles desde el propio `run.sh` mientras corre.
Si en algún caso quieren que la salida cruda de una herramienta forme parte de la evidencia, lo
correcto es que la copien a `$SANDBOX_REPORTS` — así queda adentro del tar de reportes y no sujeta
al truncado.

---

## 5. §4.3 — cómo separamos tiempo de compilación de tiempo de ejecución

Tienen razón en la objeción: **desde afuera no hay forma de detectar dónde termina el compilador.**
La capa 1, bajo la Opción 1, ni siquiera sabe que hubo una JVM: hace `sh ./run.sh` y espera. Así que
sí, **nos lo tienen que avisar ustedes**. La pregunta es por qué canal, y `stdout` no sirve: está
desviado a un archivo y es el mismo stream que el alumno puede ensuciar.

Dos formas, y les recomendamos la primera.

**(a) Un helper que ponemos nosotros en la imagen** *(recomendada)*

```sh
sandbox-fase COMPILACION
javac …
sandbox-fase PRUEBAS
java …
```

`sandbox-fase` es un programa chiquito nuestro, en `/usr/local/bin`, fuera de `/work`. Hace dos
cosas: escribe la etiqueta en `$SANDBOX_STATUS/fase` —o sea que les cubre también el punto 3 sin
escribir el `echo`— y **estampa el reloj de CPU acumulado en ese instante** en un archivo que el
alumno no puede tocar. Con eso la capa 1 reconstruye el gasto por fase al final, sin poller ni
adivinanza. Ventajas: un solo `run.sh`, una sola invocación, y el script se sigue pudiendo correr a
mano fuera del sandbox (si el helper no existe, es un no-op).

**(b) El perfil declara dos comandos en vez de uno**

```jsonc
"compileScript": "#!/bin/sh\njavac …",
"evalScript":    "#!/bin/sh\njava …"
```

La capa 1 los invoca por separado y cada uno tiene su presupuesto real, aplicado por el kernel. Es
la única forma de que el presupuesto de compilación sea un **límite** y no sólo contabilidad. Cuesta
que el script se parta en dos y que el estado entre fases tenga que quedar en disco.

**La diferencia que importa:** con (a) medimos y **no le cobramos** al alumno el tiempo de compilar,
pero el corte duro sigue siendo un techo único. Con (b) cada fase tiene su propio corte. Nuestra
lectura es que (a) alcanza para lo que ustedes querían —que un alumno con solución correcta no se
vaya a timeout por culpa del compilador— y es mucho más barata. Si prefieren (b), la construimos.

Mientras no exista ninguna de las dos, aplicamos un techo único holgado de CPU y compilar sale del
presupuesto del alumno.

---

## 6. §4.6 — `reportFormat`, nombre del archivo, y quién guarda `exitCodes`

**`reportFormat` es un enum cerrado de nuestro catálogo** (un valor desconocido es un `422` al
registrar el perfil, no una sorpresa en ejecución). Arrancamos con:

| Valor | Qué verificamos con él |
|---|---|
| `junit-xml` | que haya al menos un `<testcase>` y que los contadores cierren: que el reporte no sea un cascarón |
| `pmd-xml` | que el XML sea de PMD y que haya al menos un `<file>` analizado |
| `checkstyle-xml` | ídem, formato Checkstyle |
| `nota-json` | el JSON de la nota de ustedes: que parsee y traiga los campos mínimos |
| `opaco` | *(default)* no verificamos nada fino. Sólo vale la regla gruesa: buzón vacío = no hay éxito |

La lista crece cuando aparezca una herramienta nueva; es una fila en nuestra base, igual que el
punto 9.

**Sobre el nombre del archivo: sí, nos parece bien fijarlo, y les proponemos declararlo en el perfil
en vez de hardcodearlo.** El motivo es que JUnit no produce *un* archivo: Surefire escribe un XML por
clase de prueba. Así que en vez de un nombre suelto, un par formato + ruta:

```jsonc
"report": { "format": "junit-xml", "path": "junit/*.xml" }
```

Convenciones que sugerimos, todas relativas a `$SANDBOX_REPORTS`: `junit/*.xml` para las pruebas,
`pmd.xml` / `checkstyle.xml` para análisis, `nota.json` para el JSON de la nota. Si un perfil deja
más de una cosa (pruebas + análisis), `report` pasa a ser una lista y verificamos cada entrada.

No cambia la regla de fondo: **todo lo que quede en `/work/reports` vuelve**, esté declarado o no.
`report` es para *verificar*, no para *filtrar*.

**`exitCodes`: la tabla la definen ustedes, la guardamos nosotros.** Su intuición es correcta.
Concretamente: viaja en el `POST /profiles`, queda **pegada a esa versión del perfil** y es inmutable
como el resto. Eso es lo que hace que la entrega de un alumno de hace tres semanas siga mostrando el
mensaje que correspondía entonces, aunque hoy el `41` quiera decir otra cosa. Cambiar un mensaje =
versión nueva del perfil, sin despliegue nuestro.

---

## 7. §4.7 — el smoke test: sincrónico o asincrónico, y qué necesitamos

**Lo corremos nosotros, de nuestro lado, al registrar el perfil.** No necesitan infraestructura ni
hacer nada en el momento.

**Recomendamos asincrónico**, con el mismo patrón que las ejecuciones:

1. `POST /profiles` → `201` inmediato, con `{profileId, version, hash, estado: "EN_VALIDACION"}`
2. Corremos los dos bundles de referencia en contenedores reales (segundos, pero es una cola)
3. El perfil pasa a `VALIDADA`, o queda `RECHAZADA` con el detalle de qué falló
4. Se enteran por `GET /profiles/{id}/{version}` o, si les sirve, por un evento `PROFILE_VALIDATED`

El motivo de no hacerlo sincrónico es que sostener una request HTTP mientras corren dos contenedores
es la primera cosa que se cae cuando hay carga, y la cola ya está armada para ejecuciones. Si
prefieren la simplicidad del sincrónico y aceptan un tope de ~30 s, también se puede: díganlo y lo
hacemos así.

**Lo único que necesitamos de ustedes son los dos bundles de referencia por perfil.** Uno que sepan
que pasa y uno que sepan que falla. Sin ellos el smoke test se degrada a *"el script arranca y deja
algo en el buzón"*, que no distingue un perfil que aprueba a todo el mundo.

---

## 8. §6 — el modelo asincrónico

### 8.1 `executionId` en el `202`

De acuerdo, y ya era la idea. El cuerpo del `202 Accepted` trae `{"executionId": "..."}` y además va
en el header `Location` apuntando al `GET`. Es el **mismo id** en los tres lugares: el `202`, el
evento y el `GET`. Nunca hay un segundo identificador.

### 8.2 `intentoNro` afuera

**De acuerdo, y nos gusta más así.** Es coherente con lo que ya sostenemos: para nosotros una
ejecución es anónima y no está vinculada a nadie. Sacamos del payload `intentoNro` **y también
`entregaId`**, que arrastraba el mismo acoplamiento.

Pero entonces hay que cerrar el hueco que queda: cuando les llega el evento, tienen que poder
mapearlo a su entrega **sin consultarnos**. Les proponemos un campo opaco:

> En el `POST` de ejecución mandan un `clientRef` (string, opaco para nosotros, sin semántica). Lo
> guardamos, no lo interpretamos nunca, y **lo devolvemos tal cual** en el evento y en el `GET`.

Ustedes le meten adentro lo que quieran —`entrega:8f2/intento:2`, un UUID de su base, lo que sea— y
nosotros seguimos sin saber que existen los alumnos. Con eso pueden correlacionar aunque se pierdan
el `202`.

Consecuencia que conviene dejar escrita: las dos reglas del §6 —*un error de infraestructura no
consume intento*, *una suite que no compila tampoco*— **pasan a ser 100% cálculo de ustedes**.
Nosotros sólo damos el insumo: el `status` del evento distingue error de infraestructura de resultado
de evaluación, y eso es todo lo que hace falta para decidir.

### 8.3 El evento en inglés: aceptado, `EXECUTION_COMPLETED`

Dos cosas que van con eso:

- **Un solo evento, con `status` adentro.** `COMPLETED` no quiere decir "el alumno aprobó" ni
  siquiera "salió bien": quiere decir *"esta ejecución terminó y no va a haber más noticias"*.
  Incluye los errores de infraestructura y los timeouts. Si hubiera un `EXECUTION_FAILED` aparte, el
  día que se nos caiga algo ustedes quedarían esperando un evento que no llega. Preferimos que
  siempre llegue uno.
- **Confírmennos la convención del resto de la plataforma** —¿todos los eventos son
  `SCREAMING_SNAKE` en inglés?— para alinear también el nombre del tópico. Proponemos
  `sandbox.executions` y nos adaptamos a lo que ya exista.

### 8.4 El `GET` mapeado al `executionId`

Confirmado: `GET /api/v1/sandbox/executions/{executionId}` devuelve el reporte completo. El evento
lleva el resumen; el detalle se busca acá. Queda como respaldo si se pierden un mensaje.

---

## 9. §7.4 — la tabla de la banda `40–59` sobre la marcha

**De acuerdo, y sí: es agregar un código a la base.** Con una precisión que sale de §4.1: como los
perfiles son inmutables y versionados, agregar un código es **una versión nueva del perfil**, no un
`UPDATE`. Sigue siendo instantáneo y sin despliegue nuestro, y a cambio una entrega vieja conserva el
mensaje que le correspondía.

Mientras tanto no se rompe nada: un código de la banda `40–59` que no esté en la tabla se muestra
como *"detenido por la evaluación"* genérico. Nunca es un error y nunca pierde el reporte.

---

## 10. §7.7 — la lista de `/libs`, y el `0` para OK

El `0` para OK ya es el contrato y ya está construido: `0` significa *"llegué al final, mirá el
reporte"*, nunca *"el alumno aprobó"*. Con eso alcanza para arrancar.

Sobre la lista, una sola advertencia para que no los agarre desprevenidos: **agregar una herramienta
no es agregar una fila.** El contenedor no tiene red, así que la herramienta tiene que estar horneada
en la imagen: es construir una imagen nueva, versionarla, publicarla en el catálogo (§4.2) y que los
perfiles apunten a ella. Tiene tiempo de preparación de nuestro lado.

Por eso es el pedido que más conviene adelantar, aunque llegue incompleto. Con que nos digan *"JUnit
5, ArchUnit, PMD"* y las versiones mayores ya podemos empezar a hornear; los detalles se ajustan
después.

---

## Lo que queda pendiente de decisión

| # | Punto | Quién decide |
|---|---|---|
| 1 | `sandbox-fase` (helper) vs. dos comandos en el perfil, para separar compilación de evaluación (§5) | ustedes |
| 2 | Smoke test asincrónico *(recomendado)* vs. sincrónico con tope (§7) | ustedes |
| 3 | `clientRef` opaco como reemplazo de `entregaId` / `intentoNro` (§8.2) | ustedes |
| 4 | Convención de nombres de eventos y tópico de la plataforma (§8.3) | ustedes + notificaciones |
| 5 | Los dos bundles de referencia por perfil (§7) | ustedes |
| 6 | La lista de `/libs` con versiones (§10) | ustedes |
| 7 | El paquete reservado de los tests, por desafío (§2) | ustedes |

Nada de esto bloquea el refactor de la capa 1, que ya está construido y probado contra Docker real.
