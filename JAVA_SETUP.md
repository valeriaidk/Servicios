# Requisitos del entorno — Extech Utilitarios

Guía para desarrolladores nuevos que clonen este proyecto.

---

## Versión oficial del proyecto

| Tecnología   | Versión requerida |
|-------------|-------------------|
| Java        | **21** (LTS)      |
| Distribución recomendada | Eclipse Temurin 21 |
| Spring Boot | 3.3.4             |
| Maven       | 3.9+ (o usar `./mvnw`) |

---

## 1. Instalar Java 21 (Eclipse Temurin)

### Opción A — Adoptium (recomendado, todas las plataformas)

1. Ir a: https://adoptium.net/temurin/releases/?version=21
2. Seleccionar tu sistema operativo y arquitectura.
3. Descargar el instalador `.msi` (Windows) o `.pkg` (macOS) o `.tar.gz` (Linux).
4. Ejecutar el instalador. En Windows, marcar la opción **"Set JAVA_HOME variable"**.

### Opción B — SDKMAN (macOS / Linux)

```bash
# Instalar SDKMAN
curl -s "https://get.sdkman.io" | bash
source "$HOME/.sdkman/bin/sdkman-init.sh"

# Instalar Temurin 21
sdk install java 21.0.5-tem

# Activarlo como versión por defecto
sdk default java 21.0.5-tem
```

### Opción C — Winget (Windows)

```powershell
winget install EclipseAdoptium.Temurin.21.JDK
```

---

## 2. Configurar JAVA_HOME

### Windows

1. Abrir **Configuración del sistema avanzada** → **Variables de entorno**.
2. En Variables del sistema, crear o editar `JAVA_HOME`:
   ```
   C:\Program Files\Eclipse Adoptium\jdk-21.x.x.x-hotspot
   ```
3. En la variable `Path`, agregar al inicio:
   ```
   %JAVA_HOME%\bin
   ```
4. Abrir una terminal nueva y verificar:
   ```powershell
   java -version
   # openjdk version "21.x.x" ...
   echo %JAVA_HOME%
   ```

### macOS / Linux

Agregar al archivo `~/.zshrc` (o `~/.bashrc`):

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)   # macOS con jenv
# o
export JAVA_HOME=/path/to/jdk-21                   # Linux

export PATH=$JAVA_HOME/bin:$PATH
```

Luego recargar:
```bash
source ~/.zshrc
java -version
```

---

## 3. Verificar que VS Code reconozca el JDK

### Extensiones requeridas

Al abrir el proyecto, VS Code sugerirá instalar las extensiones del archivo
`.vscode/extensions.json`. Aceptar la instalación. Las más importantes son:

- **Extension Pack for Java** (`vscjava.vscode-java-pack`)
- **Spring Boot Extension Pack** (`vmware.vscode-spring-boot`)

### Verificación en VS Code

1. Abrir la paleta de comandos: `Ctrl+Shift+P` (o `Cmd+Shift+P` en macOS).
2. Buscar: **"Java: Configure Java Runtime"**.
3. Confirmar que aparece **Java 21** como runtime activo del proyecto.
4. Si dice *"JDK not found"*, revisar que `JAVA_HOME` esté correctamente configurado
   y **reiniciar VS Code** completamente.

### Solución rápida si VS Code no detecta el JDK

Agregar esto en tu `settings.json` de **usuario** (no del proyecto):

```json
{
  "java.configuration.runtimes": [
    {
      "name": "JavaSE-21",
      "path": "C:\\Program Files\\Eclipse Adoptium\\jdk-21.x.x.x-hotspot",
      "default": true
    }
  ]
}
```

> **Importante:** Esta configuración va en tu settings.json personal
> (`~/.config/Code/User/settings.json` en Linux/macOS o
> `%APPDATA%\Code\User\settings.json` en Windows),
> **no en el `.vscode/settings.json` del proyecto**, para no hardcodear rutas locales.

---

## 4. Compilar y ejecutar

```bash
# Opción A: con el Maven Wrapper del proyecto (no necesita Maven instalado)
./mvnw spring-boot:run

# Opción B: con Maven instalado en el sistema
mvn spring-boot:run

# Compilar sin ejecutar
./mvnw clean package -DskipTests
```

---

## 5. Error "JDK 'temurin-21' is missing" — Causa y solución

Este error ocurre cuando VS Code lee la configuración de IntelliJ IDEA
(`.idea/misc.xml`) y no encuentra un JDK registrado exactamente con ese nombre.

**Solución definitiva:** El archivo `.idea/misc.xml` ya fue corregido en este
repositorio para usar el nombre genérico `"21"` en lugar de `"temurin-21"`.
Además, `misc.xml` está ahora en `.idea/.gitignore` para que cada desarrollador
gestione su propia configuración local de IDE.

Si el error persiste después de hacer `git pull`:

```bash
# Verificar que la corrección está aplicada
grep "project-jdk-name" .idea/misc.xml
# Debe mostrar: project-jdk-name="21"  (no "temurin-21")

# Si tu copia local aún tiene el valor viejo, restaurar desde el repo
git checkout .idea/misc.xml
```

---

## 6. Estructura de configuración del proyecto

```
Servicios/
├── .vscode/
│   ├── settings.json      # Config de VS Code compartida (sin rutas locales)
│   └── extensions.json    # Extensiones recomendadas
├── .idea/
│   ├── misc.xml           # JDK genérico "21" — no hardcodea distribución
│   └── .gitignore         # misc.xml excluido del repo para futuras modificaciones
├── pom.xml                # java.version=21 via Spring Boot parent
└── JAVA_SETUP.md          # Esta guía
```
