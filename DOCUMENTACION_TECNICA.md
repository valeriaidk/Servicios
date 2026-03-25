# Documentación Técnica — Extech Utilitarios
**Versión 1.0 · Extech · Marzo 2026**

> Documentación completa del sistema Extech Utilitarios. Cubre arquitectura, base de datos, seguridad, servicios, templates, pruebas y estado final del proyecto.

---

## Índice

1. [Introducción y visión general](#1-introducción-y-visión-general)
2. [Cronología de construcción (6 etapas)](#2-cronología-de-construcción-6-etapas)
3. [Arquitectura de capas](#3-arquitectura-de-capas)
4. [Mapa de archivos del proyecto](#4-mapa-de-archivos-del-proyecto)
5. [Servicios — explicación detallada](#5-servicios--explicación-detallada)
   - [5.1 RENIEC — Consulta por DNI](#51-reniec--consulta-por-dni)
   - [5.2 SUNAT — Consulta por RUC](#52-sunat--consulta-por-ruc)
   - [5.3 SMS — Envío de mensajes de texto](#53-sms--envío-de-mensajes-de-texto)
   - [5.4 Correo — Envío de correo electrónico](#54-correo--envío-de-correo-electrónico)
6. [Stored Procedures](#6-stored-procedures)
7. [Tablas de base de datos](#7-tablas-de-base-de-datos)
8. [Seguridad — JWT y API Key](#8-seguridad--jwt-y-api-key)
9. [Consumo y límites de plan](#9-consumo-y-límites-de-plan)
10. [Templates de correo y SMS](#10-templates-de-correo-y-sms)
11. [Guía de pruebas con Postman](#11-guía-de-pruebas-con-postman)
12. [Catálogo de mensajes y errores](#12-catálogo-de-mensajes-y-errores)
13. [Estado final del proyecto](#13-estado-final-del-proyecto)

---

## 1. Introducción y visión general

**Extech Utilitarios** es una plataforma de servicios que centraliza el consumo de APIs externas bajo un modelo de suscripción por planes. Los clientes se registran, reciben un API Key único, y lo utilizan para consultar servicios como RENIEC, SUNAT, SMS y correo electrónico. El sistema controla el uso mediante planes con límites mensuales configurables.

### Tecnologías principales

| Capa | Tecnología |
|------|------------|
| Backend | Spring Boot 3.x (Java 21) |
| Frontend | React 18 |
| Base de datos | SQL Server 2022 (`BDExtech_Utilitarios`) |
| Autenticación | JWT firmado (HS256) + API Key (BCrypt hash) |
| Proveedor RENIEC/SUNAT | Decolecta |
| Proveedor SMS | Infobip |
| Proveedor Correo | Microsoft Graph OAuth2 (o SMTP) |
| Cifrado de tokens externos | AES-256-CBC |
| Documentación API | Swagger / OpenAPI 3 |

### Servicios disponibles

| Código | Nombre | Tipo | Proveedor |
|--------|--------|------|-----------|
| `RENIEC_DNI` | Consulta de persona por DNI | Síncrono | Decolecta |
| `SUNAT_RUC` | Consulta de contribuyente por RUC | Síncrono | Decolecta |
| `SMS_SEND` | Envío de mensaje de texto | Síncrono | Infobip |
| `CORREO_ENVIO` | Envío de correo electrónico | Síncrono | Microsoft Graph / SMTP |

### Restricciones inamovibles del sistema

El sistema fue diseñado con 8 reglas de cumplimiento obligatorio que no pueden relajarse:

**R1 — JWT + API Key son obligatorios para consumir servicios.**
Los endpoints `/servicios/**` requieren ambas credenciales simultáneamente:
- `Authorization: Bearer <jwt>` → identifica al usuario y su plan.
- `X-API-Key: <clave>` → autoriza el consumo del servicio.
Ambas deben pertenecer al mismo usuario. JWT es la fuente de identidad; API Key es la autorización de canal. Ninguno puede sustituir al otro.

**R2 — 1 request = 1 consumo exacto.**
Cada llamada a cualquier servicio registra exactamente 1 entrada en `IT_Consumo`, sin excepción. Esto incluye requests fallidos.

**R3 — `IT_Consumo` es la única tabla de auditoría.**
No se crean tablas adicionales de logging. Toda la información de consumo vive en `IT_Consumo`.

**R4 — SMS y Correo no duplican estructuras.**
Comparten `IT_Consumo`, `IT_Template` y `EnvioBaseService`. Sin tablas ni vistas separadas por canal.

**R5 — JWT contiene únicamente `userId` y `planId`.**
Sin email, nombre, roles ni datos sensibles. El claim `sub` lleva el email como identificador de sujeto.

**R6 — Cero credenciales en el código fuente.**
Todas las claves se gestionan en `application.properties` con referencias a variables de entorno (`${VARIABLE}`).

**R7 — Simplicidad sobre sobreingeniería.**
La solución más simple y mantenible siempre se prefiere sobre abstracciones innecesarias.

**R8 — El API Key nunca se almacena en texto plano.**
`IT_Token_Usuario.ApiKey` almacena exclusivamente el hash BCrypt. El valor plano se entrega al usuario únicamente en el momento de generación o regeneración. Los tokens externos se almacenan cifrados con AES-256.

---

## 2. Cronología de construcción (6 etapas)

### Etapa 1 — Base del proyecto y autenticación

Se configuró el proyecto Spring Boot 3.x con Java 21, la conexión a SQL Server y el módulo de autenticación completo:

- Configuración de `pom.xml` con dependencias: Spring Web, Security, WebFlux (WebClient), JJWT, Lombok, Springdoc OpenAPI, Microsoft Graph SDK, MS JDBC driver.
- Creación de `AuthController`, `AuthService`, `AuthRepository` con los endpoints `POST /auth/registro` y `POST /auth/login`.
- Implementación de `JwtUtil` (firma HS256, claims mínimos: `userId` y `planId`).
- Implementación de `ApiKeyUtil` (generación UUID, hash BCrypt, verificación).
- Creación de `SecurityConfig` con Spring Security stateless, filtros JWT y API Key.
- Validaciones de registro: email único, contraseña mínimo 8 caracteres, mayúscula y número.

### Etapa 2 — Servicios RENIEC y SUNAT

Se implementaron los servicios de consulta a la API de Decolecta:

- `ReniecService` y `SunatService` con validación local de DNI (8 dígitos) y RUC (11 dígitos, inicio 10 o 20) antes de llamar al proveedor externo.
- `ReniecRepository` y `SunatRepository` usan `uspResolverApiExternaPorUsuarioYFuncion` para obtener endpoint, token AES y cabecera de autorización desde BD.
- Integración con WebClient (reactivo) con timeout configurable (60 segundos por defecto).
- `AesUtil` para descifrar tokens de Decolecta almacenados con AES-256-CBC en `IT_ApiExternaFuncion.Token`. Se implementó `descifrarConFallback()` para compatibilidad con tokens aún en texto plano.
- Registro en `IT_Consumo` tanto en éxito como en fallo (R2).
- Respuestas enriquecidas: `ReniecResponse` y `SunatResponse` incluyen contexto del plan (`nombreUsuario`, `plan`, `consumoActual`, `limiteMaximo`).

### Etapa 3 — Servicios SMS y Correo con lógica unificada

Se implementaron SMS y Correo usando herencia desde `EnvioBaseService`:

- `EnvioBaseService` (abstract) en `util/` contiene la lógica compartida: validar plan, resolver contenido del template, registrar consumo.
- `SmsService extends EnvioBaseService` → envío a Infobip vía HTTP.
- `CorreoService extends EnvioBaseService` → envío vía Microsoft Graph OAuth2 (o SMTP como alternativa).
- Soporte de dos modos en cada envío: **TEMPLATE** (código de template + variables) e **INLINE** (contenido directo en el request).
- `PlantillaUtil` para renderizado de templates con sustitución de `{{variable}}`.

### Etapa 4 — Seguridad de filtros y control de consumo

Se refinó la cadena de seguridad y el control de límites:

- `JwtFilter` corre primero: extrae `userId`, `planId` y email del JWT, establece el `SecurityContext`. Si el JWT es inválido o está ausente en `/servicios/**`, retorna 401.
- `ApiKeyFilter` corre después (solo en `/servicios/**`): extrae el `X-API-Key`, busca el usuario correspondiente por hash BCrypt en `IT_Token_Usuario`, verifica que coincida con el `userId` del JWT.
- `uspPlanValidarLimiteUsuario` valida el límite mensual. Si `PuedeContinuar=0`, lanza `LimiteAlcanzadoException`.
- `PlanContext` record encapsula el resultado de validación: `plan`, `consumoActual`, `limiteMaximo`.
- Lógica de resolución del nombre del usuario (`obtenerNombrePorId`) al inicio de cada servicio para registrar en `IT_Consumo.UsuarioRegistro`.

### Etapa 5 — Templates, Swagger y manejo de errores

Se refactorizaron los templates, se limpió Swagger y se mejoró el manejo global de errores:

- **Templates migrados a classpath**: El cuerpo HTML/TXT ya no se lee de `IT_Template.CuerpoTemplate` en BD. Vive en `src/main/resources/templates/correo/` y `templates/sms/`. La BD conserva únicamente `AsuntoTemplate` (asunto del correo).
- `PlantillaUtil.cargarDesdeClasspath()` carga archivos desde el classpath usando `ClassPathResource`.
- `EnvioBaseService.resolverContenido()` actualizado: construye la ruta por convención (`templates/{correo|sms}/{codigo}.{html|txt}`), carga del classpath y renderiza con `PlantillaUtil.renderizar()`.
- **Swagger**: eliminado completamente `bearerAuth`. Solo permanece `apiKeyAuth` (header `X-API-Key`). Limpieza de `@SecurityRequirement(name = "bearerAuth")` en los 6 controladores afectados.
- **GlobalExceptionHandler**: añadidos manejadores para 405 (método no permitido), 400 (JSON inválido), 422 (parámetro ausente), 404 (endpoint no encontrado). Mejorado el mensaje de `LIMITE_ALCANZADO` con opciones de upgrade de plan.
- `application.properties`: añadidas propiedades para que Spring genere 404 en lugar de redirigir a `/error`.

### Etapa 6 — Templates de correo con logo y SQL de límites

Se finalizaron los templates HTML y los scripts SQL:

- Logo de Extech añadido al encabezado naranja (`#f97316`) de los 5 templates HTML: `otp.html`, `bienvenida.html`, `limite-alcanzado.html`, `cambio-plan.html`, `regeneracion-api-key.html`. Imagen servida desde Firebase Storage.
- Script SQL `insert_limites_todos_planes.sql` creado con inserts idempotentes (`NOT EXISTS`) para los 4 planes × 4 funciones. ENTERPRISE no tiene registros en `IT_PlanFuncionLimite` (sin límite real).
- Corrección del SP `uspConsumoRegistrar`: parámetro `@UsuarioRegistro` cambiado de `INT` a `VARCHAR(200)` para almacenar el nombre del usuario.

---

## 3. Arquitectura de capas

```
[Cliente/UI o Postman]
        │
        ▼
[Controller]          ← Recibe HTTP, valida DTOs con @Valid, delega al Service
        │              No contiene lógica de negocio
        ▼
[Service]             ← Toda la lógica de negocio:
        │                - Validar input localmente
        │                - Verificar límite de plan
        │                - Llamar API externa (WebClient)
        │                - Registrar consumo (IT_Consumo)
        │                - Renderizar templates
        │                - Retornar respuesta enriquecida
        ▼
[Repository]          ← Solo ejecuta Stored Procedures vía JdbcTemplate / SimpleJdbcCall
        │              Sin ORM (JPA/Hibernate). Los SP son la base del acceso a datos.
        ▼
[SQL Server 2022]     ← Stored Procedures + tablas IT_*
        BDExtech_Utilitarios
```

### Regla de dependencias entre paquetes

Cada módulo de funcionalidad (`reniec/`, `sunat/`, `sms/`, `correo/`, `auth/`, `admin/`) puede depender de:

- `domain/` — repositorios compartidos (Consumo, Usuario, Token, Plan)
- `security/` — filtros y utilidades JWT/ApiKey
- `config/` — configuración global
- `util/` — utilidades puras (AesUtil, ValidadorUtil, PlantillaUtil, EnvioBaseService)
- `exception/` — excepciones de negocio

**Nunca** un módulo de funcionalidad depende de otro módulo de funcionalidad.

### Cadena de filtros Spring Security

```
Request HTTP a /servicios/**
        │
        ▼
JwtFilter (primero)
   ├── ¿Hay Authorization: Bearer <token>?
   │       NO → 401 { "codigo": "JWT_REQUERIDO" }
   │       SÍ → validar token con JwtUtil.validar()
   │              ├── Inválido o vencido → 401 { "codigo": "JWT_INVALIDO" / "JWT_EXPIRADO" }
   │              └── Válido → extraer userId, planId, email
   │                          → establecer SecurityContext
   │                          → añadir atributo "userId" en request
        │
        ▼
ApiKeyFilter (segundo, solo /servicios/**)
   ├── ¿Hay X-API-Key: <clave>?
   │       NO → 401 { "codigo": "API_KEY_INVALIDA" }
   │       SÍ → ApiKeyUtil.resolverUsuarioId(apiKeyPlano)
   │              ├── NULL (no coincide ningún hash) → 401 { "codigo": "API_KEY_INVALIDA" }
   │              └── userId encontrado
   │                   ├── ¿Coincide con userId del JWT? NO → 401 API_KEY_INVALIDA
   │                   └── SÍ → continuar al Controller
        │
        ▼
Controller → Service → Repository → SQL Server
```

---

## 4. Mapa de archivos del proyecto

### Estructura completa

```
PUtilitarios/
├── CLAUDE.md                           ← Especificación técnica de producción
├── DOCUMENTACION_TECNICA.md            ← Este archivo
│
├── servicios/                          ← Módulo Spring Boot
│   ├── pom.xml
│   └── src/
│       ├── main/
│       │   ├── java/pe/extech/utilitarios/
│       │   │   ├── ExtechUtilitariosApplication.java
│       │   │   │
│       │   │   ├── auth/
│       │   │   │   ├── AuthController.java          ← POST /auth/registro, /auth/login
│       │   │   │   ├── AuthService.java             ← Lógica de registro y login
│       │   │   │   ├── AuthRepository.java          ← SP: validar acceso, plan activo
│       │   │   │   ├── UsuarioController.java       ← GET /usuario/perfil, etc.
│       │   │   │   ├── UsuarioService.java          ← Perfil, cambio de plan, regenerar API Key
│       │   │   │   └── dto/
│       │   │   │       ├── LoginRequest.java
│       │   │   │       ├── RegistroRequest.java
│       │   │   │       └── AuthResponse.java
│       │   │   │
│       │   │   ├── reniec/
│       │   │   │   ├── IReniecService.java
│       │   │   │   ├── ReniecController.java        ← GET /servicios/reniec/dni?numero=
│       │   │   │   ├── ReniecService.java           ← Validar, llamar Decolecta, registrar
│       │   │   │   ├── ReniecRepository.java        ← SP: resolverConfiguracion
│       │   │   │   └── dto/
│       │   │   │       ├── ReniecRequest.java       ← record: numero
│       │   │   │       └── ReniecResponse.java      ← record + ReniecData
│       │   │   │
│       │   │   ├── sunat/
│       │   │   │   ├── ISunatService.java
│       │   │   │   ├── SunatController.java         ← POST /servicios/sunat/ruc
│       │   │   │   ├── SunatService.java
│       │   │   │   ├── SunatRepository.java
│       │   │   │   └── dto/
│       │   │   │       ├── SunatRequest.java        ← record: ruc
│       │   │   │       └── SunatResponse.java       ← record + SunatData
│       │   │   │
│       │   │   ├── sms/
│       │   │   │   ├── ISmsService.java
│       │   │   │   ├── SmsController.java           ← POST /servicios/sms/enviar
│       │   │   │   ├── SmsService.java              ← extends EnvioBaseService
│       │   │   │   ├── SmsRepository.java
│       │   │   │   └── dto/
│       │   │   │       ├── SmsRequest.java          ← to, template|mensaje, variables
│       │   │   │       └── SmsResponse.java         ← ok, mensaje, proveedor, referencia
│       │   │   │
│       │   │   ├── correo/
│       │   │   │   ├── ICorreoService.java
│       │   │   │   ├── CorreoController.java        ← POST /servicios/correo/enviar
│       │   │   │   ├── CorreoService.java           ← extends EnvioBaseService
│       │   │   │   ├── CorreoRepository.java
│       │   │   │   └── dto/
│       │   │   │       ├── CorreoRequest.java       ← to, template|asunto+cuerpo, variables
│       │   │   │       └── CorreoResponse.java      ← ok, mensaje, proveedor, referencia
│       │   │   │
│       │   │   ├── admin/
│       │   │   │   └── AdminController.java         ← /admin/usuarios, /admin/planes, etc.
│       │   │   │
│       │   │   ├── domain/
│       │   │   │   ├── consumo/
│       │   │   │   │   └── ConsumoRepository.java   ← registrar, validarLimite, historial
│       │   │   │   ├── usuario/
│       │   │   │   │   └── UsuarioRepository.java   ← guardarOActualizar, obtenerNombrePorId
│       │   │   │   ├── token/
│       │   │   │   │   └── TokenRepository.java     ← insertar, obtenerActivo, desactivarYCrear
│       │   │   │   └── plan/
│       │   │   │       └── PlanRepository.java      ← listar planes activos
│       │   │   │
│       │   │   ├── security/
│       │   │   │   ├── JwtFilter.java               ← Extrae y valida JWT en cada request
│       │   │   │   ├── ApiKeyFilter.java            ← Valida X-API-Key en /servicios/**
│       │   │   │   ├── JwtUtil.java                 ← generar(), validar(), extraerClaims()
│       │   │   │   └── ApiKeyUtil.java              ← generar(), verificar(), resolverUsuarioId()
│       │   │   │
│       │   │   ├── config/
│       │   │   │   ├── SecurityConfig.java          ← Reglas Spring Security + orden filtros
│       │   │   │   ├── SwaggerConfig.java           ← OpenAPI 3, solo apiKeyAuth
│       │   │   │   ├── CorsConfig.java              ← CORS para frontend React
│       │   │   │   ├── PasswordEncoderConfig.java   ← @Bean BCryptPasswordEncoder
│       │   │   │   └── SwaggerAutoOpen.java         ← Abre Swagger UI al iniciar (dev)
│       │   │   │
│       │   │   ├── exception/
│       │   │   │   ├── GlobalExceptionHandler.java  ← @RestControllerAdvice, manejo global
│       │   │   │   ├── ErrorResponse.java           ← DTO: ok, codigo, mensaje, detalles
│       │   │   │   ├── LimiteAlcanzadoException.java
│       │   │   │   ├── ApiKeyInvalidaException.java
│       │   │   │   ├── UsuarioInactivoException.java
│       │   │   │   ├── ProveedorExternoException.java
│       │   │   │   └── ServicioNoDisponibleException.java
│       │   │   │
│       │   │   └── util/
│       │   │       ├── EnvioBaseService.java        ← Abstract: lógica compartida SMS/Correo
│       │   │       ├── PlantillaUtil.java           ← renderizar(), cargarDesdeClasspath()
│       │   │       ├── ValidadorUtil.java           ← validarDni(), validarRuc(), bit()
│       │   │       ├── AesUtil.java                 ← cifrar(), descifrar(), descifrarConFallback()
│       │   │       └── PlanContext.java             ← record: plan, consumoActual, limiteMaximo
│       │   │
│       │   └── resources/
│       │       ├── application.properties
│       │       └── templates/
│       │           ├── correo/
│       │           │   ├── otp.html                 ← Código de verificación
│       │           │   ├── otp.txt                  ← Fallback texto plano OTP
│       │           │   ├── bienvenida.html          ← Bienvenida al registrarse
│       │           │   ├── limite-alcanzado.html    ← Aviso de límite mensual
│       │           │   ├── cambio-plan.html         ← Confirmación de cambio de plan
│       │           │   └── regeneracion-api-key.html ← Aviso de nuevo API Key
│       │           └── sms/
│       │               ├── otp.txt                  ← OTP por SMS
│       │               └── notificacion.txt         ← SMS genérico
│       └── test/
│
└── sql/
    ├── insert_limites_todos_planes.sql         ← Límites por plan × función (idempotente)
    ├── insert_limites_sms_correo.sql           ← Límites específicos para SMS y Correo
    ├── alter_sp_consumo_registrar_varchar.sql  ← Cambio VARCHAR en @UsuarioRegistro
    ├── fix_uspApiKeyDesactivarYCrear.sql       ← Corrección SP regeneración API Key
    ├── insert_template_correo_otp.sql          ← Registro IT_Template OTP
    ├── update_template_correo_otp_v2.sql       ← Actualización CuerpoTemplate → referencia classpath
    ├── fix_decolecta_reniec_auth.sql           ← Corrección Autorizacion en IT_ApiExternaFuncion
    ├── update_decolecta_token.sql              ← Actualización token Decolecta
    └── check_decolecta_token_status.sql        ← Verificación estado token
```

### Rutas en IntelliJ y descripción detallada de cada archivo

> **Cómo navegar en IntelliJ:** en el panel `Project` (izquierda), expandir el nodo del proyecto y seguir el "click path" indicado para cada archivo. También puedes usar `Ctrl+Shift+N` (Windows/Linux) o `Cmd+Shift+O` (Mac) para buscar el archivo por nombre directamente.

**Raíz del módulo backend en IntelliJ:**
```
servicios
 └── src
      └── main
           └── java
                └── pe.extech.utilitarios
```
Todos los click paths a continuación parten desde `pe.extech.utilitarios`.

---

#### `ExtechUtilitariosApplication.java`
**Click path IntelliJ:**
```
pe.extech.utilitarios.ExtechUtilitariosApplication
```

**Qué hace:** Punto de entrada de la aplicación. Contiene el `main()` con `@SpringBootApplication`. Arranca el contexto Spring, configura el servidor embebido Tomcat en el puerto 8080.

**Interviene en el flujo:** Solo al iniciar la aplicación. No interviene en ningún request en tiempo de ejecución.

**Se relaciona con:** Todo — arranca el contexto que carga todos los beans (`@Service`, `@Component`, `@Repository`, `@RestController`, filtros).

---

#### `auth/AuthController.java`
**Click path IntelliJ:**
```
pe.extech.utilitarios.auth.AuthController
```
**Líneas clave:** clase → L19 | `registro()` → L31 | `login()` → L43

**Qué hace:** Expone dos endpoints públicos (sin autenticación):
- `POST /api/v1/auth/registro` → delega a `AuthService.registrar()`
- `POST /api/v1/auth/login` → delega a `AuthService.login()`

Valida el body con `@Valid`. No contiene ninguna lógica de negocio.

**Interviene en el flujo:** Es el primer punto de entrada Java cuando llega un request de registro o login. Los filtros `JwtFilter` y `ApiKeyFilter` no actúan sobre `/auth/**` (están en la lista de `permitAll()`).

**Se relaciona con:** `AuthService` (lo llama directamente), `RegistroRequest` y `LoginRequest` (DTOs de entrada), `AuthResponse` (DTO de salida), `GlobalExceptionHandler` (captura excepciones lanzadas por el service).

---

#### `auth/AuthService.java`
**Click path IntelliJ:**
```
pe.extech.utilitarios.auth.AuthService
```
**Líneas clave:** clase → L24 | `registrar()` → L42 | `login()` → L79

**Qué hace:** Toda la lógica de registro y login.
- `registrar()`: verifica email único → hashea password BCrypt → llama `UsuarioRepository.guardarOActualizar()` (SP `uspIT_UsuarioGuardarActulizar`) → genera API Key (UUID 32 chars) → guarda hash en `TokenRepository.insertar()` → retorna el valor plano UNA SOLA VEZ en `AuthResponse`.
- `login()`: obtiene usuario por email → verifica password BCrypt → verifica `Activo=1` → obtiene `planId` → genera JWT con `JwtUtil.generar()` → retorna JWT en `AuthResponse`. El API Key **no se retorna** (hash one-way).

**Interviene en el flujo:** Paso 2 (registro) y Paso 3 (login) del flujo del sistema.

**Se relaciona con:** `AuthRepository` (obtener usuario por email, obtener plan activo), `UsuarioRepository` (crear usuario), `TokenRepository` (insertar token), `JwtUtil` (generar JWT), `ApiKeyUtil` (generar API Key), `BCryptPasswordEncoder` (hashear/verificar passwords).

---

#### `auth/AuthRepository.java`
**Click path IntelliJ:**
```
pe.extech.utilitarios.auth.AuthRepository
```
**Líneas clave:** clase → L17 | `obtenerPorEmail()` → L25 | `existeEmail()` → L32 | `obtenerPlanActivo()` → L42

**Qué hace:** Acceso a datos para autenticación. Ejecuta SP vía `JdbcTemplate`:
- `obtenerPorEmail(email)` → SP `uspUsuarioValidarAcceso` → retorna `UsuarioId`, `PasswordHash`, `Activo`, `Nombre`, `Apellido`.
- `existeEmail(email)` → consulta directa a `IT_Usuario`.
- `obtenerPlanActivo(usuarioId)` → SP `uspPlanObtenerConfiguracionCompleta` → retorna `PlanId`, `Nombre` del plan.

**Interviene en el flujo:** Dentro de `AuthService`, en la validación de credenciales del login.

**Se relaciona con:** `AuthService` (lo usa), SQL Server tablas `IT_Usuario` e `IT_PlanUsuario` (vía SP).

---

#### `auth/UsuarioController.java`
**Click path IntelliJ:**
```
pe.extech.utilitarios.auth.UsuarioController
```
**Líneas clave:** clase → L21 | `perfil()` → L33 | `regenerarApiKey()` → L48 | `cambiarPlan()` → L67 | `historial()` → L84 | `resumen()` → L99

**Qué hace:** Expone los endpoints del área de usuario (requieren autenticación):
- `GET /api/v1/usuario/perfil` → `UsuarioService.obtenerPerfil()`
- `PUT /api/v1/usuario/perfil` → `UsuarioService.actualizarPerfil()`
- `POST /api/v1/usuario/api-key/regenerar` → `UsuarioService.regenerarApiKey()`
- `POST /api/v1/usuario/cambiar-plan` → `UsuarioService.cambiarPlan()`
- `GET /api/v1/usuario/consumo` → `UsuarioService.obtenerHistorial()`
- `GET /api/v1/usuario/consumo/resumen` → `UsuarioService.obtenerResumen()`

**Interviene en el flujo:** Recibe el request después de que `JwtFilter` lo autentica. Obtiene el `userId` del `SecurityContext` o del atributo del request.

**Se relaciona con:** `UsuarioService`, `JwtFilter` (establece la identidad antes de llegar aquí).

---

#### `auth/UsuarioService.java`
**Click path IntelliJ:**
```
pe.extech.utilitarios.auth.UsuarioService
```
**Líneas clave:** clase → L18 | `obtenerPerfil()` → L28 | `regenerarApiKey()` → L37 | `cambiarPlan()` → L48 | `obtenerHistorial()` → L63

**Qué hace:** Lógica de negocio del área de usuario. Gestiona perfil, cambio de plan y regeneración de API Key. En la regeneración: llama `ApiKeyUtil.generar()` para obtener el nuevo par `(plano, hash)`, llama `TokenRepository.desactivarYCrear()` (SP `uspApiKeyDesactivarYCrear`), retorna el valor plano al cliente.

**Interviene en el flujo:** Después de autenticación JWT, en operaciones de gestión del usuario.

**Se relaciona con:** `UsuarioRepository`, `TokenRepository`, `ConsumoRepository`, `ApiKeyUtil`, `JwtUtil` (en cambio de plan, para emitir nuevo JWT con el `planId` actualizado).

---

#### `auth/dto/LoginRequest.java`
**Click path IntelliJ:**
```
pe.extech.utilitarios.auth.dto.LoginRequest
```

**Qué hace:** Record Java con validaciones para el body del login: `email` (no blank, formato email) y `password` (no blank).

**Interviene en el flujo:** Spring deserializa el JSON del body en este record antes de llegar al controller. Si la validación falla, `GlobalExceptionHandler` devuelve 422.

---

#### `auth/dto/RegistroRequest.java`
**Click path IntelliJ:**
```
pe.extech.utilitarios.auth.dto.RegistroRequest
```

**Qué hace:** Record con validaciones para el registro: `nombre`, `apellido`, `email` obligatorios; `password` con mínimo 8 chars, al menos una mayúscula y un número (`@Pattern`). Campos opcionales: `telefono`, `razonSocial`, `ruc`.

---

#### `auth/dto/AuthResponse.java`
**Click path IntelliJ:**
```
pe.extech.utilitarios.auth.dto.AuthResponse
```

**Qué hace:** Record de respuesta unificado para registro y login. Campos: `ok`, `jwt` (null en registro), `apiKey` (null en login), `usuario` (record anidado `UsuarioDto`), `plan` (record anidado `PlanDto`), `mensaje`.

---

#### `reniec/ReniecController.java`
**Click path IntelliJ:**
```
pe.extech.utilitarios.reniec.ReniecController
```
**Líneas clave:** clase → L20 | `consultarDni()` → L43

**Qué hace:** Expone `GET /api/v1/servicios/reniec/dni`. Recibe el query param `numero` con `@RequestParam`. Obtiene el `userId` del atributo que `JwtFilter` dejó en el request (`request.getAttribute("userId")`). Delega a `ReniecService.consultarDni()`.

**Interviene en el flujo:** Cuarto punto (después de JwtFilter → ApiKeyFilter → SecurityContext). Es el primer código Java de negocio en ejecutarse para este servicio.

**Se relaciona con:** `ReniecService`, `JwtFilter` (establece `userId` en el request), `ApiKeyFilter` (valida la API Key antes de llegar aquí).

---

#### `reniec/ReniecService.java`
**Click path IntelliJ:**
```
pe.extech.utilitarios.reniec.ReniecService
```
**Líneas clave:** clase → L39 | `consultarDni()` → L72 | `verificarLimite()` → L202 | `mapearRespuesta()` → L235

**Qué hace:** Toda la lógica del servicio RENIEC. Flujo completo: resolver nombre del usuario → validar DNI localmente → resolver configuración del proveedor → validar límite de plan → descifrar token AES → construir URL y header → llamar Decolecta con WebClient → mapear respuesta → registrar en IT_Consumo → retornar ReniecResponse.

**Interviene en el flujo:** Pasos 1 al 11 del flujo RENIEC detallado en la sección 5.1.

**Se relaciona con:** `ReniecRepository` (resolver config del proveedor), `ConsumoRepository` (validar límite y registrar consumo), `UsuarioRepository` (obtener nombre), `AesUtil` (descifrar token), `ValidadorUtil` (validar DNI), `PlanContext` (encapsula resultado de validación del plan).

---

#### `reniec/ReniecRepository.java`
**Click path IntelliJ:**
```
pe.extech.utilitarios.reniec.ReniecRepository
```
**Líneas clave:** clase → L19 | `resolverConfiguracion()` → L42

**Qué hace:** Ejecuta `uspResolverApiExternaPorUsuarioYFuncion` con código `RENIEC_DNI`. Retorna un `Map<String, Object>` con: `ApiServicesFuncionId`, `EndpointExterno`, `Token` (cifrado AES), `Autorizacion` (template `Bearer {TOKEN}`), `TiempoConsulta`.

**Interviene en el flujo:** Dentro de `ReniecService`, antes de llamar a Decolecta.

**Se relaciona con:** `ReniecService` (lo usa), SQL Server tabla `IT_ApiExternaFuncion` (vía SP).

---

#### `reniec/dto/ReniecRequest.java`
**Click path IntelliJ:**
```
pe.extech.utilitarios.reniec.dto.ReniecRequest
```

**Qué hace:** Record con un solo campo `numero`. Sirve como referencia del modelo de entrada. En la implementación actual el DNI llega como `@RequestParam`, no como body JSON.

---

#### `reniec/dto/ReniecResponse.java`
**Click path IntelliJ:**
```
pe.extech.utilitarios.reniec.dto.ReniecResponse
```

**Qué hace:** Record de respuesta con `@JsonInclude(NON_NULL)`. Contiene: `ok`, `codigo`, `mensaje`, `usuarioId`, `nombreUsuario`, `plan`, `consumoActual`, `limiteMaximo`, `apiServicesFuncionId`, `servicioNombre`, `servicioCodigo`, `servicioDescripcion`, y el record anidado `ReniecData` con `dni`, `nombres`, `apellidoPaterno`, `apellidoMaterno`, `nombreCompleto`.

**Nota:** `@JsonInclude(NON_NULL)` hace que `limiteMaximo` no aparezca en el JSON si es `null` (ENTERPRISE).

---

#### `sunat/SunatController.java`, `SunatService.java`, `SunatRepository.java`
**Click path IntelliJ:**
```
pe.extech.utilitarios.sunat.SunatController
pe.extech.utilitarios.sunat.SunatService
pe.extech.utilitarios.sunat.SunatRepository
```
**Líneas clave — SunatController:** clase → L19 | `consultarRuc()` → L44
**Líneas clave — SunatService:** clase → L40 | `consultarRuc()` → L73 | `verificarLimite()` → L177

**Qué hacen:** Estructura idéntica a RENIEC. `SunatController` expone `POST /api/v1/servicios/sunat/ruc`. `SunatService` valida el RUC con `ValidadorUtil.validarRuc()` antes de llamar a Decolecta. `SunatRepository` resuelve la configuración del proveedor con código `SUNAT_RUC`. `SunatResponse` incluye record anidado `SunatData` con `ruc`, `razonSocial`, `estado`, `condicion`, `direccion`.

---

#### `sms/SmsController.java`
**Click path IntelliJ:**
```
pe.extech.utilitarios.sms.SmsController
```
**Líneas clave:** clase → L21 | `enviar()` → L46

**Qué hace:** Expone `POST /api/v1/servicios/sms/enviar`. Recibe `SmsRequest` validado con `@Valid`. Delega a `SmsService.enviar()`.

**Se relaciona con:** `SmsService`, `SmsRequest` (DTO entrada), `SmsResponse` (DTO salida).

---

#### `sms/SmsService.java`
**Click path IntelliJ:**
```
pe.extech.utilitarios.sms.SmsService
```
**Líneas clave:** clase → L51 | `enviar()` → L78 | `resolverContenidoSms()` → L179 | `enviarInfobip()` → L232

**Qué hace:** Extiende `EnvioBaseService`. Implementa el método abstracto `enviar()` con la lógica específica de Infobip: construye el payload JSON de Infobip, llama la API con `WebClient`, extrae el `messageId` de la respuesta como referencia. El resto del flujo (resolver nombre, validar plan, resolver template, registrar consumo) lo hereda de `EnvioBaseService`.

**Interviene en el flujo:** Después de que `EnvioBaseService` resuelve el contenido del template y valida el plan.

**Se relaciona con:** `EnvioBaseService` (hereda de él), `SmsRepository` (resolver config de Infobip), `ConsumoRepository` (registrar consumo), `PlantillaUtil` (renderizar template).

---

#### `sms/dto/SmsRequest.java`
**Click path IntelliJ:**
```
pe.extech.utilitarios.sms.dto.SmsRequest
```

**Qué hace:** Record con campos: `to` (número destino, obligatorio), `template` (código del template, opcional), `mensaje` (contenido directo, opcional), `variables` (`Map<String, Object>`, opcional). Al menos uno de `template` o `mensaje` debe estar presente — validación en el service.

---

#### `sms/dto/SmsResponse.java`
**Click path IntelliJ:**
```
pe.extech.utilitarios.sms.dto.SmsResponse
```

**Qué hace:** Record con `@JsonInclude(NON_NULL)`. Campos: `ok`, `mensaje` (texto de confirmación), `proveedor` (ej: "INFOBIP"), `referencia` (ID del mensaje en Infobip), `nombreUsuario`, `plan`, `consumoActual`, `limiteMaximo`.

---

#### `correo/CorreoController.java`, `CorreoService.java`, `CorreoRepository.java`
**Click path IntelliJ:**
```
pe.extech.utilitarios.correo.CorreoController
pe.extech.utilitarios.correo.CorreoService
pe.extech.utilitarios.correo.CorreoRepository
```
**Líneas clave — CorreoController:** clase → L21 | `enviar()` → L47
**Líneas clave — CorreoService:** clase → L59 | `enviar()` → L97 | `obtenerAccessToken()` → L189 | `enviarGraph()` → L241

**Qué hacen:** Estructura idéntica a SMS. `CorreoController` expone `POST /api/v1/servicios/correo/enviar`. `CorreoService` extiende `EnvioBaseService` e implementa el envío vía Microsoft Graph OAuth2 (o SMTP como alternativa). `CorreoRepository` resuelve la config del proveedor de correo. `CorreoRequest` añade los campos `asunto`, `cuerpoHtml` y `cuerpoTexto` para el modo INLINE.

---

#### `admin/AdminController.java`
**Click path IntelliJ:**
```
pe.extech.utilitarios.admin.AdminController
```
**Líneas clave:** clase → L33 | `listarPlanes()` → L49 | `activarUsuario()` → L61 | `desactivarUsuario()` → L76

**Qué hace:** Endpoints de administración protegidos por rol ADMIN:
- `GET /admin/usuarios` — listar todos los usuarios
- `PUT /admin/usuarios/{id}/activar` — activar usuario (llama `uspUsuarioActivarDesactivar` con `Activo=1`)
- `PUT /admin/usuarios/{id}/desactivar` — desactivar usuario (llama `uspUsuarioActivarDesactivar` con `Activo=0`)
- `GET /admin/planes` — listar planes activos
- `POST /admin/planes` — crear plan
- `GET /admin/consumo/global` — estadísticas globales

**Interviene en el flujo:** Solo accesible si `JwtFilter` asignó `ROLE_ADMIN` al usuario (email en lista de admins).

**Se relaciona con:** `UsuarioRepository`, `PlanRepository`, `ConsumoRepository`, `JwtFilter` (asigna el rol).

---

#### `domain/consumo/ConsumoRepository.java`
**Click path IntelliJ:**
```
pe.extech.utilitarios.domain.consumo.ConsumoRepository
```
**Líneas clave:** clase → L25 | `registrar(7 params)` → L42 | `validarLimitePlan()` → L82 | `obtenerHistorial()` → L104 | `obtenerTotalMensual()` → L156

**Qué hace:** El repositorio más usado del sistema. Punto central de toda la auditoría. Métodos:
- `registrar(usuarioId, funcionId, request, response, exito, esConsulta, nombreUsuario)` → SP `uspConsumoRegistrar` con 7 params. `nombreUsuario` es el `Nombre` del usuario (VARCHAR 200), no su ID ni email.
- `registrar(usuarioId, funcionId, request, response, exito, esConsulta)` → delegado: llama `UsuarioRepository.obtenerNombrePorId()` internamente y luego llama al SP.
- `validarLimitePlan(usuarioId, funcionId)` → SP `uspPlanValidarLimiteUsuario`. Retorna `PuedeContinuar`, `ConsumoActual`, `LimiteMaximo`, `NombrePlan`, `MensajeError`.
- `obtenerTotalMensual(usuarioId, funcionId)` → SP `uspConsumoObtenerTotalMensualPorUsuario`.
- `obtenerHistorial(usuarioId, pageNumber, pageSize)` → SP `uspConsumoObtenerHistorialPorUsuario`.

**Interviene en el flujo:** Dos momentos clave en cada request de servicio: (1) antes de llamar al proveedor externo, para validar el límite del plan; (2) después de llamar al proveedor, para registrar el resultado. **Siempre se llama** (R2).

**Se relaciona con:** Todos los servicios (`ReniecService`, `SunatService`, `SmsService`, `CorreoService`, `EnvioBaseService`). Es el repositorio compartido más importante.

---

#### `domain/usuario/UsuarioRepository.java`
**Click path IntelliJ:**
```
pe.extech.utilitarios.domain.usuario.UsuarioRepository
```
**Líneas clave:** clase → L19 | `guardarOActualizar()` → L37 | `validarAcceso()` → L64 | `obtenerNombrePorId()` → L106

**Qué hace:** Métodos:
- `guardarOActualizar(nombre, apellido, email, passwordHash, telefono, razonSocial, ruc)` → SP `uspIT_UsuarioGuardarActulizar`. Crea el usuario y le asigna el plan FREE automáticamente. Retorna `UsuarioId` y `PlanId`.
- `obtenerNombrePorId(usuarioId)` → consulta directa `SELECT Nombre FROM IT_Usuario WHERE UsuarioId = ?`. Retorna el `Nombre` del usuario para registrar en `IT_Consumo.UsuarioRegistro`.

**Interviene en el flujo:** Al inicio de cada servicio (`ReniecService`, `SunatService`, `SmsService`, `CorreoService`) para obtener el nombre del usuario. También en el registro.

---

#### `domain/token/TokenRepository.java`
**Click path IntelliJ:**
```
pe.extech.utilitarios.domain.token.TokenRepository
```
**Líneas clave:** clase → L18 | `insertar()` → L34 | `obtenerActivo()` → L46 | `desactivarYCrear()` → L73

**Qué hace:** Gestión de tokens (API Keys) en `IT_Token_Usuario`:
- `insertar(usuarioId, hashApiKey, fechaInicio, fechaFin)` → SP `usp_InsertarTokenUsuario`.
- `obtenerActivo(usuarioId)` → SP `uspObtenerVigentesPorTokenUsuario`. Retorna el token activo.
- `desactivarYCrear(usuarioId, nuevoHashApiKey, fechaInicio, fechaFin)` → SP `uspApiKeyDesactivarYCrear`. Usado en la regeneración manual.

**Interviene en el flujo:** Al registrar (insertar token) y al regenerar API Key (desactivar anterior, crear nuevo).

---

#### `domain/plan/PlanRepository.java`
**Click path IntelliJ:**
```
pe.extech.utilitarios.domain.plan.PlanRepository
```
**Líneas clave:** clase → L13 | `listarPlanes()` → L65

**Qué hace:** Lista los planes activos desde `IT_Plan`. Usado por `AdminController` para el endpoint `GET /admin/planes`.

---

#### `security/JwtFilter.java`
**Click path IntelliJ:**
```
pe.extech.utilitarios.security.JwtFilter
```
**Líneas clave:** clase → L32 | `doFilterInternal()` → L51

**Qué hace:** Filtro Spring que se ejecuta en **cada request** (excepto los de `permitAll()`). Lógica:
1. Extrae el header `Authorization: Bearer <token>`.
2. Si está presente, llama `JwtUtil.validar(token)`.
3. Si es válido: extrae `userId`, `planId`, `email` de los claims; crea un `UsernamePasswordAuthenticationToken` con `ROLE_ADMIN` o `ROLE_CLIENTE` según si el email está en la lista de admins; establece el `SecurityContext`; guarda `userId` como atributo del request (`request.setAttribute("userId", userId)`).
4. Si el token es inválido o ha expirado: escribe directamente el JSON de error 401 en el response y detiene la cadena (no pasa al siguiente filtro ni al controller).

**Interviene en el flujo:** Primero en ejecutarse de todos los filtros de negocio. Establece la identidad del usuario para el resto del request.

**Se relaciona con:** `JwtUtil` (validación de token), `SecurityConfig` (donde se registra como filtro), `ApiKeyFilter` (corre después de JwtFilter).

---

#### `security/ApiKeyFilter.java`
**Click path IntelliJ:**
```
pe.extech.utilitarios.security.ApiKeyFilter
```
**Líneas clave:** clase → L49 | `doFilterInternal()` → L61

**Qué hace:** Filtro que actúa **solo sobre `/api/v1/servicios/**`**. Lógica:
1. Extrae el header `X-API-Key`.
2. Llama `ApiKeyUtil.resolverUsuarioId(apiKeyPlano)` — recorre `IT_Token_Usuario` verificando BCrypt.
3. Si no encuentra coincidencia → 401 `API_KEY_INVALIDA`.
4. Verifica que el `usuarioId` del API Key coincida con el `userId` del JWT (cross-check de seguridad).
5. Si coinciden → continúa al Controller.

**Interviene en el flujo:** Segundo en ejecutarse (después de JwtFilter), solo para endpoints de servicios.

**Se relaciona con:** `ApiKeyUtil` (resolver usuarioId), `JwtFilter` (el userId del JWT ya está disponible como atributo del request cuando ApiKeyFilter corre).

---

#### `security/JwtUtil.java`
**Click path IntelliJ:**
```
pe.extech.utilitarios.security.JwtUtil
```
**Líneas clave:** clase → L21 | `generar()` → L36 | `validar()` → L54 | `extraerUserId()` → L62 | `extraerPlanId()` → L66 | `extraerEmail()` → L70

**Qué hace:** Utilidad para JWT con JJWT 0.12+. Métodos:
- `generar(userId, planId, email)` → construye JWT HS256 con claims `userId` + `planId`, subject = email, expiración configurable (default 1 hora).
- `validar(token)` → parsea y verifica la firma. Lanza `JwtException` si inválido.
- `extraerUserId(claims)`, `extraerPlanId(claims)`, `extraerEmail(claims)` → getters de claims.
- `esValido(token)` → wrapper que devuelve `boolean`.

La clave secreta se inyecta desde `${extech.jwt.secret}` en `application.properties`.

---

#### `security/ApiKeyUtil.java`
**Click path IntelliJ:**
```
pe.extech.utilitarios.security.ApiKeyUtil
```
**Líneas clave:** clase → L26 | `generar()` → L37 | `verificar()` → L46 | `resolverUsuarioId()` → L57

**Qué hace:** Utilidad para API Keys. Métodos:
- `generar()` → `UUID.randomUUID().toString().replace("-", "")` → 32 chars hexadecimales → hashea con BCrypt → retorna `ApiKeyGenerado(plano, hash)`.
- `verificar(plano, hash)` → `BCryptPasswordEncoder.matches(plano, hash)`.
- `resolverUsuarioId(plano)` → consulta `IT_Token_Usuario` (activos, no eliminados, no vencidos) → itera verificando BCrypt hasta encontrar coincidencia → retorna `UsuarioId` o `null`.

> `resolverUsuarioId()` hace BCrypt en cada token activo — es computacionalmente intensivo por diseño (seguridad). Si hay muchos usuarios, se puede optimizar con caché en memoria (mejora futura).

---

#### `config/SecurityConfig.java`
**Click path IntelliJ:**
```
pe.extech.utilitarios.config.SecurityConfig
```
**Líneas clave:** clase → L25 | `filterChain()` → L34

**Qué hace:** Configuración central de Spring Security. Define:
- Política stateless (sin sesiones HTTP).
- CSRF deshabilitado (API REST).
- Reglas de autorización: `/auth/**`, `/swagger-ui/**`, `/v3/api-docs/**` → público; todo lo demás → autenticado.
- Orden de filtros: `ApiKeyFilter` antes que `JwtFilter`, pero `JwtFilter` antes que `UsernamePasswordAuthenticationFilter` → la cadena efectiva de ejecución es `JwtFilter` primero, luego `ApiKeyFilter`.
- `PasswordEncoderConfig` provee el `@Bean BCryptPasswordEncoder`.

**Interviene en el flujo:** En el arranque de la aplicación, al configurar la cadena de seguridad. No interviene en cada request directamente, pero define las reglas que los filtros aplican.

---

#### `config/SwaggerConfig.java`
**Click path IntelliJ:**
```
pe.extech.utilitarios.config.SwaggerConfig
```
**Líneas clave:** clase → L12 | `openAPI()` → L15

**Qué hace:** Configura la documentación OpenAPI 3. Define un solo esquema de seguridad: `apiKeyAuth` (tipo API Key, header `X-API-Key`). `bearerAuth` fue eliminado por completo. La UI de Swagger muestra el campo "API Key" en el botón "Authorize".

URL de acceso: `http://localhost:8080/swagger-ui.html`

---

#### `config/CorsConfig.java`
**Click path IntelliJ:**
```
pe.extech.utilitarios.config.CorsConfig
```

**Qué hace:** Habilita CORS para el frontend React (típicamente `http://localhost:3000` en desarrollo). Permite los métodos GET, POST, PUT, DELETE y el header `X-API-Key`.

---

#### `config/SwaggerAutoOpen.java`
**Click path IntelliJ:**
```
pe.extech.utilitarios.config.SwaggerAutoOpen
```

**Qué hace:** Abre automáticamente el navegador en `http://localhost:8080/swagger-ui.html` al arrancar la aplicación en modo desarrollo. Usa `Desktop.browse()`. Solo relevante en entorno local.

---

#### `util/EnvioBaseService.java`
**Click path IntelliJ:**
```
pe.extech.utilitarios.util.EnvioBaseService
```
**Líneas clave:** clase → L23 | `resolverNombreUsuario()` → L38 | `validarPlan()` → L64 | `resolverContenido()` → L113 | `resolverAsunto()` → L140

**Qué hace:** Clase abstracta que evita la duplicación de lógica entre SMS y Correo. Lógica compartida implementada:
- `resolverContenido(funcionId, canal, templateCodigo, variables, version)`: si hay `templateCodigo`, construye la ruta classpath por convención (`templates/{correo|sms}/{codigo}.{html|txt}`), carga con `PlantillaUtil.cargarDesdeClasspath()`, renderiza con `PlantillaUtil.renderizar()`. En modo INLINE, retorna `variables.get("cuerpo")` directamente.
- `resolverAsunto(funcionId, canal, templateCodigo)`: consulta `IT_Template.AsuntoTemplate` en BD. Solo para EMAIL.
- `registrarConsumo(...)`: delegado a `ConsumoRepository.registrar()`.

Método abstracto que cada subclase implementa: `enviar()` — la lógica específica del proveedor (Infobip para SMS, Microsoft Graph para Correo).

**Interviene en el flujo:** En el nucleo de los servicios de envío, entre la validación del plan y la llamada al proveedor externo.

**Se relaciona con:** `SmsService` y `CorreoService` (heredan de él), `PlantillaUtil` (renderizado), `ConsumoRepository` (registro de consumo).

---

#### `util/PlantillaUtil.java`
**Click path IntelliJ:**
```
pe.extech.utilitarios.util.PlantillaUtil
```
**Líneas clave:** clase → L24 | `cargarDesdeClasspath()` → L33 | `renderizar()` → L59

**Qué hace:** Dos métodos:
- `renderizar(template, variables)`: itera el `Map<String, Object>` y reemplaza cada `{{clave}}` por su valor. Simple y sin dependencias de librerías de templating externas.
- `cargarDesdeClasspath(rutaRelativa)`: usa `ClassPathResource` de Spring. Si el archivo no existe, lanza `IllegalArgumentException` con el mensaje: `"Template no encontrado en el classpath: '{ruta}'. Verifica que el archivo exista en src/main/resources/{ruta}."`.

**Interviene en el flujo:** Dentro de `EnvioBaseService.resolverContenido()`, al cargar y renderizar el template.

---

#### `util/ValidadorUtil.java`
**Click path IntelliJ:**
```
pe.extech.utilitarios.util.ValidadorUtil
```
**Líneas clave:** clase → L7 | `bit()` → L20 | `validarDni()` → L47 | `validarRuc()` → L53

**Qué hace:** Métodos estáticos de validación. Cada `validar*()` lanza `IllegalArgumentException` con mensaje específico si falla:
- `validarDni(dni)`: regex `^[0-9]{8}$`.
- `validarRuc(ruc)`: regex `^(10|20)[0-9]{9}$`.
- `esTelefonoValido(tel)`: regex `^\+?[0-9]{9,15}$`.
- `bit(Object value)`: maneja los tipos `Boolean`, `Integer` (1/0), `Short` (1/0) que el driver MS JDBC puede retornar para columnas BIT de SQL Server.

**Interviene en el flujo:** Al inicio de cada servicio de consulta, antes de llamar al proveedor externo. El objetivo es evitar cobros por datos malformados.

---

#### `util/AesUtil.java`
**Click path IntelliJ:**
```
pe.extech.utilitarios.util.AesUtil
```
**Líneas clave:** clase → L22 | `cifrar()` → L43 | `descifrar()` → L58 | `descifrarConFallback()` → L102

**Qué hace:** Cifrado/descifrado AES-256-CBC. La clave se inyecta desde `${extech.aes.clave}` en `application.properties`. Método clave:
- `descifrarConFallback(texto)`: intenta descifrar como AES-256. Si el Base64 es inválido o el descifrado falla (token en texto plano legacy), retorna el texto original sin lanzar excepción. Permite convivir con tokens cifrados y no cifrados en la misma BD.

**Interviene en el flujo:** En `ReniecService` y `SunatService`, después de obtener `Token` desde `IT_ApiExternaFuncion`, antes de usarlo en el header `Authorization` hacia Decolecta.

---

#### `util/PlanContext.java`
**Click path IntelliJ:**
```
pe.extech.utilitarios.util.PlanContext
```
**Líneas clave:** clase (record) → L11

**Qué hace:** Record inmutable con tres campos: `plan` (nombre del plan activo, ej: "FREE"), `consumoActual` (conteo de consumos exitosos del mes **antes** de este request), `limiteMaximo` (nullable — null significa ENTERPRISE/sin límite).

**Importante:** La respuesta al cliente debe mostrar `consumoActual + 1` para reflejar que este request ya fue contado. El SP valida el límite **antes** de que el consumo sea registrado.

**Interviene en el flujo:** Lo crea `verificarLimite()` dentro de cada servicio y lo pasa al método `mapearRespuesta()` para enriquecer el JSON de respuesta.

---

#### `exception/GlobalExceptionHandler.java`
**Click path IntelliJ:**
```
pe.extech.utilitarios.exception.GlobalExceptionHandler
```
**Líneas clave:** clase → L22 | `handleLimite()` → L39 | `handleApiKey()` → L70 | `handleUsuarioInactivo()` → L77

**Qué hace:** `@RestControllerAdvice` que intercepta todas las excepciones no capturadas en controllers y services. Garantiza que el cliente siempre reciba un JSON estructurado con `ok`, `codigo`, `mensaje` y `detalles` (nunca un stack trace).

| Excepción capturada | HTTP | Código |
|---------------------|------|--------|
| `LimiteAlcanzadoException` | 429 | `LIMITE_ALCANZADO` + opciones de upgrade según plan |
| `ApiKeyInvalidaException` | 401 | `API_KEY_INVALIDA` |
| `UsuarioInactivoException` | 403 | `USUARIO_INACTIVO` |
| `ProveedorExternoException` | 502 | `PROVEEDOR_ERROR` |
| `ServicioNoDisponibleException` | 503 | `SERVICIO_NO_DISPONIBLE` |
| `IllegalArgumentException` | 422 | `CAMPO_INVALIDO` |
| `HttpRequestMethodNotSupportedException` | 405 | `METODO_NO_PERMITIDO` |
| `MissingServletRequestParameterException` | 422 | `CAMPO_REQUERIDO` |
| `HttpMessageNotReadableException` | 400 | `JSON_INVALIDO` |
| `NoResourceFoundException` | 404 | `RECURSO_NO_ENCONTRADO` |
| `MethodArgumentNotValidException` | 422 | `CAMPO_REQUERIDO` |
| `Exception` (genérico) | 500 | `ERROR_INTERNO` |

**Interviene en el flujo:** Al final — captura cualquier excepción que burbujee desde Service o Repository y la convierte en respuesta HTTP.

---

#### `exception/LimiteAlcanzadoException.java`
**Click path IntelliJ:**
```
pe.extech.utilitarios.exception.LimiteAlcanzadoException
```
**Líneas clave:** clase → L5 | `constructor()` → L9

**Qué hace:** Excepción de negocio. Lleva `consumoActual`, `limiteMaximo` y `plan` como campos para que `GlobalExceptionHandler` pueda incluirlos en el JSON de `detalles` y generar el mensaje de upgrade correcto.

---

#### `resources/application.properties`
**Click path IntelliJ:**
```
servicios > src > main > resources > application.properties
```

**Qué hace:** Configuración central de la aplicación. Contiene: URL de conexión SQL Server, referencias a variables de entorno para JWT secret y AES key, configuración SMTP, lista de emails admin, branding de la plataforma. **No debe versionarse con valores reales** en producción.

Propiedades relevantes para el comportamiento del sistema:
- `spring.mvc.throw-exception-if-no-handler-found=true` → permite que `GlobalExceptionHandler` capture los 404 en lugar de que Spring los redirija a `/error`.
- `spring.web.resources.add-mappings=false` → complemento del anterior.
- `extech.proveedor.decolecta.timeout-ms=60000` → timeout de WebClient para llamadas a Decolecta.

---

#### `resources/templates/`
**Click path IntelliJ:**
```
servicios > src > main > resources > templates
```

**Estructura:**
```
templates/
├── correo/
│   ├── otp.html                  ← Variables: code, minutes, brand_app_name, brand_support_email
│   ├── otp.txt                   ← Fallback texto plano para OTP
│   ├── bienvenida.html           ← Variables: nombre, email, brand_app_name, brand_support_email
│   ├── limite-alcanzado.html     ← Variables: nombre, plan_actual, consumo_actual, link_cambio_plan, ...
│   ├── cambio-plan.html          ← Variables: nombre, plan_anterior, plan_nuevo, fecha_inicio, ...
│   └── regeneracion-api-key.html ← Variables: nombre, fecha_regeneracion, brand_app_name, ...
└── sms/
    ├── otp.txt                   ← Variables: brand_app_name, code, minutes
    └── notificacion.txt          ← Variables: brand_app_name, mensaje
```

**Los archivos son cargados en tiempo de ejecución** por `PlantillaUtil.cargarDesdeClasspath()`. Si se modifica un archivo HTML hay que **recompilar y redeployar** la aplicación para que el cambio tome efecto (son recursos empaquetados en el JAR).

**Todos los HTML comparten la misma estructura visual:** encabezado naranja `#f97316` con el logo de Extech desde Firebase Storage, cuerpo blanco con el contenido específico de cada template, y pie de página gris claro.

---

## 5. Servicios — explicación detallada

### 5.1 RENIEC — Consulta por DNI

**Endpoint:** `GET /api/v1/servicios/reniec/dni?numero=<dni>`

**Headers requeridos:**
```
Authorization: Bearer <jwt_usuario>
X-API-Key: <api_key_usuario>
```

**Flujo completo en `ReniecService.consultarDni(usuarioId, dniParam)`:**

1. Resolver `nombreUsuario` desde `UsuarioRepository.obtenerNombrePorId(usuarioId)` — disponible en todos los paths de error para registrar en `IT_Consumo.UsuarioRegistro`.
2. Llamar `ValidadorUtil.validarDni(dniParam)` — lanza `IllegalArgumentException` (422) si no tiene exactamente 8 dígitos numéricos. Evita cobros al proveedor por datos inválidos.
3. Llamar `ReniecRepository.resolverConfiguracion(usuarioId)` — ejecuta `uspResolverApiExternaPorUsuarioYFuncion`. Retorna: `ApiServicesFuncionId`, `EndpointExterno`, `Token` (AES), `Autorizacion` (template con `{TOKEN}`).
4. Llamar `verificarLimite(usuarioId, funcionId, payload, nombreUsuario)` — ejecuta `uspPlanValidarLimiteUsuario`. Si `PuedeContinuar=false`, registra consumo fallido y lanza `LimiteAlcanzadoException` (429).
5. Descifrar token: `aesUtil.descifrarConFallback(config.get("Token"))`.
6. Construir `authHeader`: reemplazar `{TOKEN}` en la columna `Autorizacion` por el token real.
7. Construir `urlFinal`: si el endpoint ya contiene `?`, concatenar el DNI directamente; si no, añadir `?numero=<dni>`.
8. Llamar a Decolecta con `WebClient.builder().baseUrl(urlFinal).defaultHeader(Authorization, authHeader).build().get().retrieve()...` con timeout configurable.
9. Mapear respuesta de Decolecta: buscar campos en nodos `data`, `result` o `persona`. Campos del proveedor: `first_name`, `first_last_name`, `second_last_name`, `full_name`.
10. Registrar en `IT_Consumo` (R2): `consumoRepository.registrar(usuarioId, funcionId, payload, responseJson, exito, true, nombreUsuario)`.
11. Retornar `ReniecResponse` con `ok=true`, datos de la persona y contexto del plan.

**Campos de respuesta `ReniecResponse`:**
```json
{
  "ok": true,
  "codigo": "OPERACION_EXITOSA",
  "mensaje": "Consulta realizada correctamente.",
  "usuarioId": 2,
  "nombreUsuario": "Juan Pérez",
  "plan": "FREE",
  "consumoActual": 4,
  "limiteMaximo": 10,
  "apiServicesFuncionId": 1,
  "servicioNombre": "Consulta DNI",
  "servicioCodigo": "RENIEC_DNI",
  "servicioDescripcion": "Consulta de datos por DNI",
  "data": {
    "dni": "72537503",
    "nombres": "NAGHELY VALERIA",
    "apellidoPaterno": "QUEZADA",
    "apellidoMaterno": "BARRIGA",
    "nombreCompleto": "QUEZADA BARRIGA NAGHELY VALERIA"
  }
}
```

**Nota importante:** `limiteMaximo` es `null` cuando el plan no tiene límite (ENTERPRISE). El frontend debe manejar este caso mostrando "sin límite" en lugar de una fracción.

**Manejo de errores de Decolecta:**
- 401 de Decolecta → "PROBABLE CAUSA: Token inválido o mal configurado en BD"
- "Apikey Required" o "Limit Exceeded" → "PROBABLE CAUSA: Token sin saldo, vencido o límite agotado"
- 403 → "PROBABLE CAUSA: Token no tiene permisos para este servicio"

---

### 5.2 SUNAT — Consulta por RUC

**Endpoint:** `POST /api/v1/servicios/sunat/ruc`

**Headers requeridos:**
```
Authorization: Bearer <jwt_usuario>
X-API-Key: <api_key_usuario>
Content-Type: application/json
```

**Body:**
```json
{
  "ruc": "20100070970"
}
```

**Flujo en `SunatService.consultarRuc(usuarioId, rucParam)`:**

Flujo idéntico a RENIEC con las siguientes diferencias:
- Validación: `ValidadorUtil.validarRuc(rucParam)` — 11 dígitos, comienza con 10 o 20.
- Endpoint configurado para SUNAT en `IT_ApiExternaFuncion` (código `DECOLECTA_SUNAT`).
- Mapeo de campos de Decolecta SUNAT: `ruc`, `razonSocial`, `estado` (`ACTIVO`/`INACTIVO`), `condicion` (`HABIDO`/`NO HABIDO`), `direccion`, `ubigeo`, `tipoContribuyente`, etc.
- `EsConsulta=true` al registrar en `IT_Consumo`.

**Campos de respuesta `SunatResponse`:**
```json
{
  "ok": true,
  "codigo": "OPERACION_EXITOSA",
  "mensaje": "Consulta realizada correctamente.",
  "usuarioId": 2,
  "nombreUsuario": "Juan Pérez",
  "plan": "BASIC",
  "consumoActual": 15,
  "limiteMaximo": 100,
  "apiServicesFuncionId": 2,
  "servicioNombre": "Consulta RUC",
  "servicioCodigo": "SUNAT_RUC",
  "servicioDescripcion": "Consulta de datos por RUC",
  "data": {
    "ruc": "20100070970",
    "razonSocial": "EMPRESA EJEMPLO SAC",
    "estado": "ACTIVO",
    "condicion": "HABIDO",
    "direccion": "AV. EJEMPLO 123, LIMA"
  }
}
```

---

### 5.3 SMS — Envío de mensajes de texto

**Endpoint:** `POST /api/v1/servicios/sms/enviar`

**Headers requeridos:**
```
Authorization: Bearer <jwt_usuario>
X-API-Key: <api_key_usuario>
Content-Type: application/json
```

**Dos modos de uso:**

#### Modo TEMPLATE
```json
{
  "to": "+51999999999",
  "template": "OTP",
  "variables": {
    "brand_app_name": "Extech Utilitarios",
    "code": "483921",
    "minutes": "10"
  }
}
```

El sistema carga el archivo `src/main/resources/templates/sms/otp.txt` y sustituye las variables `{{variable}}`.

#### Modo INLINE
```json
{
  "to": "+51999999999",
  "mensaje": "Tu pedido ha sido confirmado. Gracias por usar Extech."
}
```

**Flujo en `SmsService.enviar(usuarioId, request)`:**

1. Resolver nombre del usuario.
2. `EnvioBaseService.resolverContenido()`: si hay `template`, carga desde classpath `templates/sms/{codigo}.txt` y renderiza. Si hay `mensaje`, usa el contenido directo.
3. `validarLimitePlan(usuarioId, funcionId)`.
4. Llamar a Infobip API: `POST https://api.infobip.com/sms/2/text/advanced` con el contenido resuelto y el número de destino. Token de Infobip descifrado desde `IT_ApiExternaFuncion`.
5. `registrarConsumo(usuarioId, funcionId, request, response, exito)` con `EsConsulta=false`.

**Templates SMS disponibles:**

| Código | Archivo | Variables |
|--------|---------|-----------|
| `OTP` | `sms/otp.txt` | `brand_app_name`, `code`, `minutes` |
| `NOTIFICACION` | `sms/notificacion.txt` | `brand_app_name`, `mensaje` |

**Contenido de `sms/otp.txt`:**
```
{{brand_app_name}}: Tu código de verificación es {{code}}.
Válido por {{minutes}} minutos. No compartas este código con nadie.
```

**Respuesta:**
```json
{
  "ok": true,
  "mensaje": "SMS enviado correctamente",
  "proveedor": "INFOBIP",
  "referencia": "MSG_ID_123456"
}
```

---

### 5.4 Correo — Envío de correo electrónico

**Endpoint:** `POST /api/v1/servicios/correo/enviar`

**Headers requeridos:**
```
Authorization: Bearer <jwt_usuario>
X-API-Key: <api_key_usuario>
Content-Type: application/json
```

**Dos modos de uso:**

#### Modo TEMPLATE
```json
{
  "to": "usuario@empresa.com",
  "template": "OTP",
  "variables": {
    "code": "483921",
    "minutes": "10",
    "brand_app_name": "Extech Utilitarios",
    "brand_support_email": "soporte@extech.pe"
  }
}
```

El sistema:
1. Carga `templates/correo/otp.html` del classpath.
2. Sustituye variables `{{variable}}` en el HTML.
3. Lee `AsuntoTemplate` desde `IT_Template` en BD (ej: "Código de verificación - Extech Utilitarios").

#### Modo INLINE
```json
{
  "to": "usuario@empresa.com",
  "asunto": "Notificación importante",
  "cuerpoHtml": "<p>Hola, este es un mensaje directo.</p>",
  "cuerpoTexto": "Hola, este es un mensaje directo."
}
```

**Templates de correo disponibles:**

| Código | Archivo HTML | Variables requeridas |
|--------|-------------|----------------------|
| `OTP` | `correo/otp.html` | `code`, `minutes`, `brand_app_name`, `brand_support_email` |
| `BIENVENIDA` | `correo/bienvenida.html` | `nombre`, `email`, `brand_app_name`, `brand_support_email` |
| `LIMITE-ALCANZADO` | `correo/limite-alcanzado.html` | `nombre`, `plan_actual`, `consumo_actual`, `link_cambio_plan`, `brand_app_name`, `brand_support_email` |
| `CAMBIO-PLAN` | `correo/cambio-plan.html` | `nombre`, `plan_anterior`, `plan_nuevo`, `fecha_inicio`, `brand_app_name`, `brand_support_email` |
| `REGENERACION-API-KEY` | `correo/regeneracion-api-key.html` | `nombre`, `fecha_regeneracion`, `brand_app_name`, `brand_support_email` |

**Diseño de templates HTML:**
- Encabezado naranja (`#f97316`) con logo de Extech desde Firebase Storage.
- Texto "Plataforma de servicios" en mayúsculas espaciadas.
- Nombre de la aplicación `{{brand_app_name}}` en blanco grande.
- Cuerpo blanco con contenido específico de cada template.
- Pie de página gris claro con aviso de mensaje automático.
- Compatible con clientes de correo que no soportan CSS externo (inline styles).

**Respuesta:**
```json
{
  "ok": true,
  "mensaje": "Correo enviado correctamente",
  "proveedor": "MICROSOFT_GRAPH",
  "referencia": "msg-id-abc123"
}
```

**Convención de rutas en classpath:**
```
templates/correo/{codigo_en_minusculas}.html
templates/correo/{codigo_en_minusculas}.txt   ← fallback texto plano (opcional)
templates/sms/{codigo_en_minusculas}.txt
```
El código del template se convierte a minúsculas: `OTP` → `otp`, `LIMITE-ALCANZADO` → `limite-alcanzado`.

---

## 6. Stored Procedures

Todos los SP viven en SQL Server. El backend los invoca con `JdbcTemplate` o `SimpleJdbcCall`. **No se modifican desde el código Java**: el código solo los llama con los parámetros correctos.

### SP existentes (conservados sin modificar la firma)

| SP | Descripción | Llamado desde |
|----|-------------|---------------|
| `usp_InsertarTokenUsuario` | Inserta API Key inicial al registrar usuario | `TokenRepository.insertar()` |
| `uspIT_UsuarioGuardarActulizar` | Crea/actualiza usuario + asigna plan FREE automáticamente | `UsuarioRepository.guardarOActualizar()` |
| `uspObtenerVigentesPorTokenUsuario` | Obtiene API Keys vigentes de un usuario | `TokenRepository.obtenerActivo()` |
| `uspPlanObtenerConfiguracionCompleta` | Configuración completa del plan activo | `AuthRepository.obtenerPlanActivo()` |
| `uspResolverApiExternaPorUsuarioYFuncion` | Resuelve endpoint + token + autorización del proveedor externo | `ReniecRepository`, `SunatRepository` |
| `uspUsuarioValidarAcceso` | Valida credenciales por email | `AuthRepository.obtenerPorEmail()` |

> El SP `uspIT_UsuarioGuardarActulizar` mantiene el typo original por compatibilidad.

### SP nuevos (ejecutar en SQL Server Management Studio)

#### `uspConsumoRegistrar`
Registra cada request en `IT_Consumo`. Parámetros:
- `@UsuarioId INT`
- `@ApiServicesFuncionId INT`
- `@Request VARCHAR(4000) = NULL`
- `@Response VARCHAR(4000) = NULL`
- `@Exito BIT = 1`
- `@EsConsulta BIT = 1`
- `@UsuarioRegistro VARCHAR(200) = NULL` ← almacena el nombre del usuario (no ID, no email)

Retorna: `ConsumoId` (SCOPE_IDENTITY).

> **Importante**: el parámetro original era `INT`. Se ejecutó `alter_sp_consumo_registrar_varchar.sql` para cambiarlo a `VARCHAR(200)`. Verificar en producción que este cambio fue aplicado.

#### `uspConsumoObtenerTotalMensualPorUsuario`
Cuenta consumos exitosos del mes actual. Parámetros:
- `@UsuarioId INT`
- `@ApiServicesFuncionId INT = NULL` — NULL cuenta todas las funciones

Retorna: `TotalConsumos INT`.

#### `uspPlanValidarLimiteUsuario`
Valida si el usuario puede consumir. Lógica:
1. Obtiene el plan activo del usuario desde `IT_PlanUsuario`.
2. Busca el límite en `IT_PlanFuncionLimite` para ese plan + función.
3. Si no hay registro → ENTERPRISE/sin límite → `PuedeContinuar=1`, `LimiteMaximo=NULL`.
4. Si hay límite → cuenta consumos del mes → compara con el límite.

Parámetros:
- `@UsuarioId INT`
- `@ApiServicesFuncionId INT`

Retorna:
- `PuedeContinuar BIT`
- `ConsumoActual INT`
- `LimiteMaximo INT` (NULL si sin límite)
- `NombrePlan VARCHAR(100)` (vacío si sin plan activo)
- `MensajeError NVARCHAR(500)`

> Regla 9: si `NombrePlan` viene vacío → el usuario no tiene plan activo → bloquear con "No tienes un plan activo. Contáctate con soporte."

#### `uspPlanUsuarioCambiar`
Cambia el plan activo en una transacción atómica:
1. Marca el plan anterior como `CANCELADO` con `FechaFinVigencia = GETDATE()`.
2. Inserta el nuevo plan con `EstadoSuscripcion = 'ACTIVO'`.

Parámetros: `@UsuarioId INT`, `@NuevoPlanId INT`, `@Observacion VARCHAR(500)`, `@UsuarioAccion INT`.
Retorna: datos del nuevo `IT_PlanUsuario` activo.

#### `uspApiKeyDesactivarYCrear`
Se llama solo en regeneración manual del API Key:
1. Marca el token anterior como `Activo=0` (sin eliminarlo, para auditoría).
2. Inserta el nuevo token con el hash BCrypt del nuevo API Key.

Parámetros: `@UsuarioId INT`, `@NuevoApiKey VARCHAR(500)` (hash BCrypt), `@FechaInicioVigencia DATETIME`, `@FechaFinVigencia DATETIME`.

#### `uspConsumoObtenerHistorialPorUsuario`
Historial paginado de consumos. Retorna dos result sets:
1. `TotalRegistros COUNT` — para paginación.
2. Lista paginada de `ConsumoId`, `Funcion`, `CodigoFuncion`, `Exito`, `EsConsulta`, `FechaRegistro`.

Parámetros: `@UsuarioId INT`, `@PageNumber INT = 1`, `@PageSize INT = 20`.

#### `uspUsuarioActivarDesactivar`
Activa o desactiva un usuario por ID.
Parámetros: `@UsuarioId INT`, `@Activo BIT`, `@UsuarioAccion INT = NULL`.
Retorna: `FilasAfectadas INT`.

---

## 7. Tablas de base de datos

Base de datos: `BDExtech_Utilitarios` en SQL Server 2022.

### `IT_Usuario`
| Columna | Tipo | Descripción |
|---------|------|-------------|
| `UsuarioId` | INT IDENTITY | PK |
| `Nombre` | VARCHAR(100) | Nombre del usuario |
| `Apellido` | VARCHAR(100) | Apellido del usuario |
| `Email` | VARCHAR(150) UNIQUE | Login y comunicaciones |
| `PasswordHash` | VARCHAR(256) | BCrypt del password |
| `Telefono` | VARCHAR(20) | Opcional |
| `RazonSocial` | VARCHAR(200) | Opcional (empresas) |
| `RUC` | VARCHAR(20) | Opcional |
| `Activo` | BIT | 0 = desactivado por admin |
| `Eliminado` | BIT | Borrado lógico |

> No existe columna `Rol`. La distinción Admin/Cliente se gestiona en Spring Security mediante lista de emails en `application.properties`.

### `IT_Token_Usuario`
| Columna | Tipo | Descripción |
|---------|------|-------------|
| `TokenId` | INT IDENTITY | PK |
| `UsuarioId` | INT UNIQUE FK | Un solo token activo por usuario |
| `ApiKey` | VARCHAR(256) | Hash BCrypt del API Key (nunca el valor plano) |
| `FechaInicioVigencia` | DATETIME | Inicio de validez |
| `FechaFinVigencia` | DATETIME | Fin de validez |
| `Activo` | BIT | 0 = regenerado/inválido |
| `Eliminado` | BIT | Borrado lógico |

> Ciclo de vida: generado al registrarse. `Activo=0` cuando se regenera (el anterior permanece como auditoría). Nunca se elimina físicamente.

### `IT_Plan`
| Columna | Tipo | Descripción |
|---------|------|-------------|
| `PlanId` | INT IDENTITY | PK |
| `Nombre` | VARCHAR(100) | FREE, BASIC, PRO, ENTERPRISE |
| `Descripcion` | VARCHAR(500) | |
| `PrecioMensual` | DECIMAL(10,2) | |
| `Activo` | BIT | |
| `Eliminado` | BIT | |

### `IT_PlanUsuario`
| Columna | Tipo | Descripción |
|---------|------|-------------|
| `PlanUsuarioId` | INT IDENTITY | PK |
| `UsuarioId` | INT FK | |
| `PlanId` | INT FK | |
| `FechaInicioVigencia` | DATETIME | |
| `FechaFinVigencia` | DATETIME NULL | NULL = sin vencimiento |
| `EstadoSuscripcion` | VARCHAR(50) | ACTIVO, CANCELADO, EXPIRADO |
| `Observacion` | VARCHAR(500) | |

> El plan vigente: `Activo=1` y `EstadoSuscripcion='ACTIVO'`.

### `IT_PlanFuncionLimite`
| Columna | Tipo | Descripción |
|---------|------|-------------|
| `LimiteId` | INT IDENTITY | PK |
| `PlanId` | INT FK | |
| `ApiServicesFuncionId` | INT FK | |
| `TipoLimite` | VARCHAR(50) | MENSUAL |
| `Limite` | INT | Cantidad máxima mensual |

> Constraint único en `(PlanId, ApiServicesFuncionId)`. Si no existe registro para un plan+función → sin límite (ENTERPRISE).

**Configuración de límites (según `insert_limites_todos_planes.sql`):**

| Plan | PlanId | Límite mensual |
|------|--------|---------------|
| FREE | 1 | 10 por función |
| BASIC | 2 | 100 por función |
| PRO | 3 | 1,000 por función |
| ENTERPRISE | 4 | Sin registros = sin límite |

### `IT_ApiServices`
| Columna | Tipo | Descripción |
|---------|------|-------------|
| `ApiServiceId` | INT IDENTITY | PK |
| `Nombre` | VARCHAR(100) | |
| `Codigo` | VARCHAR(50) UNIQUE | RENIEC, SUNAT, SMS, CORREO |

### `IT_ApiServicesFuncion`
| Columna | Tipo | Descripción |
|---------|------|-------------|
| `ApiServicesFuncionId` | INT IDENTITY | PK |
| `ApiServiceId` | INT FK | |
| `Nombre` | VARCHAR(100) | |
| `Codigo` | VARCHAR(50) UNIQUE | RENIEC_DNI, SUNAT_RUC, SMS_SEND, CORREO_ENVIO |
| `Endpoint` | VARCHAR(300) | Endpoint interno |
| `Metodo` | VARCHAR(10) | GET, POST |

### `IT_ApiExternaFuncion`
| Columna | Tipo | Descripción |
|---------|------|-------------|
| `ApiExternaFuncionId` | INT IDENTITY | PK |
| `Nombre` | VARCHAR(100) | |
| `Codigo` | VARCHAR(50) UNIQUE | DECOLECTA_RENIEC, DECOLECTA_SUNAT, INFOBIP_SMS |
| `Endpoint` | VARCHAR(500) | URL del proveedor externo |
| `Metodo` | VARCHAR(10) | GET, POST |
| `Token` | VARCHAR(1000) | **Token cifrado AES-256** |
| `Autorizacion` | VARCHAR(1000) | Template con `{TOKEN}`: ej `Bearer {TOKEN}` |
| `TiempoConsulta` | INT | Timeout en segundos |

> `Autorizacion` debe contener el placeholder `{TOKEN}`. Si no lo contiene, el backend logueará un error de configuración y el header `Authorization` se enviará vacío (causa 401 en Decolecta).

### `IT_Consumo`
**La tabla más importante del sistema.** Cada request = 1 fila.

| Columna | Tipo | Descripción |
|---------|------|-------------|
| `ConsumoId` | BIGINT IDENTITY | PK |
| `UsuarioId` | INT FK | Usuario que realizó el request |
| `ApiServicesFuncionId` | INT FK | Servicio consumido |
| `Request` | VARCHAR(4000) | Payload de entrada (JSON) |
| `Response` | VARCHAR(4000) | Respuesta devuelta (JSON) |
| `Exito` | BIT | 1 = exitoso; solo exitosos descuentan del límite |
| `EsConsulta` | BIT | 1 = RENIEC/SUNAT; 0 = SMS/Correo |
| `UsuarioRegistro` | VARCHAR(200) | **Nombre del usuario** (no ID ni email) |
| `FechaRegistro` | DATETIME | Timestamp del consumo |

> `UsuarioRegistro` almacena el nombre (`IT_Usuario.Nombre`) obtenido al inicio de cada servicio. Esta decisión se tomó para que los registros históricos sean legibles directamente en la tabla sin hacer JOIN.

### `IT_Template`
| Columna | Tipo | Descripción |
|---------|------|-------------|
| `TemplateId` | INT IDENTITY | PK |
| `ApiServicesFuncionId` | INT FK | SMS_SEND o CORREO_ENVIO |
| `Canal` | VARCHAR(20) | EMAIL, SMS |
| `Codigo` | VARCHAR(50) | OTP, BIENVENIDA, etc. |
| `Version` | SMALLINT | 1, 2, 3... |
| `Nombre` | VARCHAR(150) | |
| `AsuntoTemplate` | VARCHAR(500) | **Solo para EMAIL** — asunto del correo |
| `CuerpoTemplate` | VARCHAR(MAX) | Referencia al classpath (no el HTML real) |
| `CuerpoTextoTemplate` | VARCHAR(MAX) | Fallback texto plano (opcional) |

> **Decisión de diseño**: `CuerpoTemplate` ya no contiene el HTML real. Contiene solo una referencia de documentación como `<!-- gestionado en classpath: templates/correo/otp.html -->`. El HTML real vive en el classpath y lo carga `PlantillaUtil.cargarDesdeClasspath()`.

---

## 8. Seguridad — JWT y API Key

### JWT (JSON Web Token)

**Generación** en `JwtUtil.generar(userId, planId, email)`:
```java
Jwts.builder()
    .claims(Map.of("userId", userId, "planId", planId))
    .subject(email)          // solo identificador de sujeto, no dato funcional
    .issuedAt(new Date())
    .expiration(new Date(System.currentTimeMillis() + expiracionMs))
    .signWith(key)           // HS256, clave mínimo 256 bits
    .compact()
```

**Claims incluidos:**
- `userId` → identificación del usuario
- `planId` → plan vigente al momento del login
- `sub` → email del usuario (identificador)
- `iat` → fecha de emisión
- `exp` → fecha de expiración

**Expiración:** 1 hora (configurable en `extech.jwt.expiracion-ms=3600000`).

**Almacenamiento:** NO se guarda en BD. Stateless. Si el plan cambia, el JWT anterior sigue siendo válido hasta su expiración. El nuevo `planId` entra en el siguiente login.

**Dónde se usa:**
- `GET /usuario/**` y `POST /usuario/**` — solo JWT
- `GET /admin/**` — JWT con verificación de email en lista de admins
- `GET|POST /servicios/**` — JWT + API Key (ambos obligatorios)

**Roles:**
```java
// JwtFilter.java
boolean esAdmin = adminEmails.contains(email);
List<GrantedAuthority> authorities = List.of(
    new SimpleGrantedAuthority(esAdmin ? "ROLE_ADMIN" : "ROLE_CLIENTE")
);
```
La lista de emails admin viene de `extech.admin.emails=admin@extech.pe` en `application.properties`.

### API Key

**Generación** en `ApiKeyUtil.generar()`:
```java
String plano = UUID.randomUUID().toString().replace("-", ""); // 32 chars hexadecimal
String hash  = passwordEncoder.encode(plano);                 // BCrypt
return new ApiKeyGenerado(plano, hash);
```

**Almacenamiento:** solo el hash BCrypt en `IT_Token_Usuario.ApiKey`. El valor plano se entrega al usuario UNA SOLA VEZ (registro o regeneración). No es recuperable posteriormente.

**Verificación** en `ApiKeyFilter`:
```java
Integer apiKeyUsuarioId = apiKeyUtil.resolverUsuarioId(apiKeyPlano);
// resolverUsuarioId recorre IT_Token_Usuario y verifica con BCrypt.matches()
```

**Ciclo de vida:**
1. Al registrarse → se genera y entrega el valor plano.
2. En cada login → NO se retorna (hash one-way).
3. Si el usuario lo pierde → debe solicitar regeneración manual.
4. Al regenerar → el anterior queda `Activo=0`, el nuevo se genera y entrega el plano.

**Cross-check de seguridad:**
El `ApiKeyFilter` verifica que el `usuarioId` resuelto por el API Key coincida con el `userId` del JWT. Si no coinciden → 401. Esto previene que un usuario use el API Key de otro con su propio JWT.

### Tokens de proveedores externos

Los tokens de Decolecta e Infobip se almacenan cifrados en `IT_ApiExternaFuncion.Token` con AES-256-CBC. Solo se descifran en tiempo de ejecución:

```java
String tokenReal = aesUtil.descifrarConFallback(config.get("Token"));
// tokenReal nunca se loguea ni se persiste
```

La clave AES se inyecta desde `${AES_KEY}` en `application.properties`.

### Configuración Spring Security

```java
http
    .csrf(csrf -> csrf.disable())           // API REST stateless
    .sessionManagement(sm -> sm.sessionCreationPolicy(STATELESS))
    .authorizeHttpRequests(auth -> auth
        .requestMatchers("/api/v1/auth/**", "/swagger-ui/**", "/v3/api-docs/**").permitAll()
        .anyRequest().authenticated()
    )
    .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
    .addFilterBefore(apiKeyFilter, JwtFilter.class); // apiKeyFilter corre DESPUÉS de jwtFilter
```

---

## 9. Consumo y límites de plan

### Regla fundamental: 1 request = 1 consumo (R2)

Cada llamada a cualquier servicio registra exactamente 1 entrada en `IT_Consumo`, **siempre**:
- Si el DNI es inválido → `Exito=0` (pero registrado)
- Si el proveedor falla → `Exito=0` (pero registrado)
- Si el límite estaba alcanzado → `Exito=0` (pero registrado antes de lanzar la excepción)
- Si todo sale bien → `Exito=1`

Solo los consumos con `Exito=1` descuentan del límite mensual (`uspPlanValidarLimiteUsuario` solo cuenta `Exito=1`).

### Flujo de validación de límite

```
Service.método(usuarioId, ...)
    │
    ├─→ obtenerNombrePorId(usuarioId)         ← resolver nombre primero
    ├─→ ValidadorUtil.validar*(input)          ← validación local (no consume)
    ├─→ Repository.resolverConfiguracion()     ← obtener ApiServicesFuncionId
    │
    ├─→ consumoRepository.validarLimitePlan(usuarioId, funcionId)
    │       → uspPlanValidarLimiteUsuario
    │       → ¿PuedeContinuar?
    │           NO:
    │             consumoRepository.registrar(..., Exito=false)  ← registrar fallido
    │             throw LimiteAlcanzadoException(msg, actual, limite, plan)
    │           SÍ:
    │             return PlanContext(plan, consumoActual, limiteMaximo)
    │
    ├─→ [llamar API externa]
    │
    └─→ consumoRepository.registrar(..., Exito=exito)  ← registrar resultado final
```

### ¿Qué pasa cuando se alcanza el límite?

**El plan NO cambia automáticamente.** El sistema solo bloquea el acceso y muestra el mensaje de error. Para continuar, el usuario debe cambiar su plan manualmente mediante `POST /usuario/cambiar-plan`.

El mensaje de error incluye opciones de upgrade:
```
Has alcanzado el límite de consumos de tu plan FREE (10/10).
Cambia tu plan para continuar.
Para continuar puedes cambiar a: BASIC (100/mes), PRO (1.000/mes) o ENTERPRISE (sin límite).
```

### Configuración de límites por plan

Los límites se configuran en `IT_PlanFuncionLimite`. El script `insert_limites_todos_planes.sql` usa inserts idempotentes:

```sql
-- Verifica antes de insertar para evitar duplicados
INSERT INTO IT_PlanFuncionLimite (PlanId, ApiServicesFuncionId, TipoLimite, Limite, ...)
SELECT 1, ApiServicesFuncionId, 'MENSUAL', 10, ...
FROM IT_ApiServicesFuncion
WHERE Activo = 1 AND Eliminado = 0
  AND NOT EXISTS (
    SELECT 1 FROM IT_PlanFuncionLimite
    WHERE PlanId = 1 AND ApiServicesFuncionId = IT_ApiServicesFuncion.ApiServicesFuncionId
  );
```

| Plan | PlanId | Acciones requeridas |
|------|--------|---------------------|
| FREE | 1 | Insertar 4 filas (10/mes × 4 funciones) |
| BASIC | 2 | Insertar 4 filas (100/mes × 4 funciones) |
| PRO | 3 | Insertar 4 filas (1000/mes × 4 funciones) |
| ENTERPRISE | 4 | No insertar — sin límite por diseño |

### Diferencia ENTERPRISE vs otros planes

Para ENTERPRISE, `uspPlanValidarLimiteUsuario` ejecuta:
```sql
SELECT @LimiteMaximo = pfl.Limite
FROM IT_PlanFuncionLimite pfl
WHERE pfl.PlanId = @PlanId AND ...
-- No encuentra fila → @LimiteMaximo permanece NULL
```

Luego:
```sql
IF @LimiteMaximo IS NOT NULL
BEGIN
    -- Verificar consumo mensual
END
-- Si NULL: sin límite → PuedeContinuar = 1 siempre
```

En la respuesta al cliente, `limiteMaximo = null` indica "sin límite". El frontend debe manejarlo mostrando "∞" o "Sin límite" en lugar de null/undefined.

---

## 10. Templates de correo y SMS

### Arquitectura de templates

Los templates están divididos entre BD y classpath:

| Ubicación | Almacena | Leído por |
|-----------|---------|----------|
| `IT_Template` en BD | Solo `AsuntoTemplate` (asunto del correo) | `EnvioBaseService.resolverAsunto()` |
| Classpath (`src/main/resources/templates/`) | Cuerpo HTML (correo) y TXT (SMS) | `PlantillaUtil.cargarDesdeClasspath()` |

Esta separación permite:
- Modificar el asunto del correo sin redeploy (solo actualizar BD).
- Versionar el HTML del template junto con el código Java en Git.
- Templates de SMS en texto plano sin overhead de BD.

### Convención de nombres

El sistema resuelve el archivo por convención a partir del código del template:
```java
// EnvioBaseService.resolverContenido()
String extension = "EMAIL".equalsIgnoreCase(canal) ? ".html" : ".txt";
String carpeta   = "EMAIL".equalsIgnoreCase(canal) ? "correo" : "sms";
String ruta      = "templates/" + carpeta + "/" + templateCodigo.toLowerCase() + extension;
```

Ejemplos de resolución:
- `template: "OTP"`, canal EMAIL → `templates/correo/otp.html`
- `template: "BIENVENIDA"`, canal EMAIL → `templates/correo/bienvenida.html`
- `template: "OTP"`, canal SMS → `templates/sms/otp.txt`
- `template: "LIMITE-ALCANZADO"`, canal EMAIL → `templates/correo/limite-alcanzado.html`

### Renderizado con PlantillaUtil

```java
// Sustitución simple de {{variable}}
public String renderizar(String template, Map<String, Object> variables) {
    String resultado = template;
    for (Map.Entry<String, Object> entry : variables.entrySet()) {
        resultado = resultado.replace(
            "{{" + entry.getKey() + "}}",
            String.valueOf(entry.getValue())
        );
    }
    return resultado;
}
```

Si una variable del mapa no tiene correspondencia en el template, se ignora. Si el template tiene `{{variable}}` pero la variable no está en el mapa, queda como texto literal `{{variable}}` — siempre verificar que el request incluya todas las variables requeridas.

### Templates de correo: estructura HTML

Todos los templates comparten la misma estructura visual:

```html
<!-- Encabezado naranja #f97316 -->
<td style="background:#f97316; padding:28px 40px;">
  <img src="https://firebasestorage.googleapis.com/v0/b/msca-7db71.appspot.com/..."
       alt="Extech" width="160" style="display:block; margin:0 auto 14px;"/>
  <p style="color:#fff3e0; text-transform:uppercase;">Plataforma de servicios</p>
  <h1 style="color:#ffffff;">{{brand_app_name}}</h1>
</td>

<!-- Cuerpo blanco -->
<td style="padding:40px 48px;">
  <!-- Contenido específico del template -->
</td>

<!-- Pie de página -->
<td style="background:#fafafa; border-top:1px solid #f0e8e0;">
  <p>Gracias por usar <strong>{{brand_app_name}}</strong>.<br/>
  Este mensaje fue generado automáticamente...</p>
</td>
```

### Variables comunes en todos los templates de correo

| Variable | Valor típico |
|----------|-------------|
| `brand_app_name` | Extech Utilitarios |
| `brand_support_email` | soporte@extech.pe |

### Templates disponibles con sus variables específicas

**`otp.html`** — Verificación de identidad:
- `code` — Código OTP (ej: `483921`)
- `minutes` — Validez en minutos (ej: `10`)

**`bienvenida.html`** — Al registrarse:
- `nombre` — Nombre del usuario
- `email` — Correo de acceso

**`limite-alcanzado.html`** — Límite de plan agotado:
- `nombre` — Nombre del usuario
- `plan_actual` — Nombre del plan (FREE, BASIC, etc.)
- `consumo_actual` — Consumos usados este mes
- `link_cambio_plan` — URL para cambiar de plan

**`cambio-plan.html`** — Confirmación de cambio de plan:
- `nombre` — Nombre del usuario
- `plan_anterior` — Plan anterior (ej: FREE)
- `plan_nuevo` — Plan nuevo (ej: BASIC)
- `fecha_inicio` — Fecha de inicio del nuevo plan

**`regeneracion-api-key.html`** — Aviso de nuevo API Key:
- `nombre` — Nombre del usuario
- `fecha_regeneracion` — Fecha en que se generó el nuevo API Key

---

## 11. Guía de pruebas con Postman

> Cada bloque muestra exactamente lo que va en Postman: método HTTP, URL completa, headers, params, tipo de body, contenido raw, respuesta esperada y los errores más comunes para ese endpoint.

**URL base:** `http://localhost:8080/api/v1`
**Swagger UI:** `http://localhost:8080/swagger-ui.html`

**Cómo configurar en Postman:**
- `Headers` → pestaña Headers → añadir `X-API-Key` con el valor plano.
- `Body` → seleccionar `raw` → tipo `JSON` → pegar el JSON tal como aparece abajo.
- `Params` → para los query params de los GET (ej: `numero`, `pageNumber`).

---

### REQUEST 1 — Registrar usuario

| Campo | Valor |
|-------|-------|
| Método | `POST` |
| URL | `http://localhost:8080/api/v1/auth/registro` |
| Headers | `Content-Type: application/json` |
| Body type | `raw → JSON` |

**Body (raw JSON):**
```json
{
  "nombre": "Juan",
  "apellido": "Pérez",
  "email": "juan@empresa.com",
  "password": "MiClave123",
  "telefono": "+51987654321",
  "razonSocial": "Empresa SAC",
  "ruc": "20100070970"
}
```
@UsuarioInactivoException

> Campos opcionales: `telefono`, `razonSocial`, `ruc`. Campos obligatorios: `nombre`, `apellido`, `email`, `password`.
> Regla de contraseña: mínimo 8 caracteres, al menos una mayúscula y al menos un número.

**Respuesta 200 — Éxito:**
```json
{
  "ok": true,
  "jwt": null,
  "apiKey": "a1b2c3d4e5f6g7h8i9j0k1l2m3n4o5p6",
  "usuario": {
    "usuarioId": 2,
    "nombre": "Juan",
    "apellido": "Pérez",
    "email": "juan@empresa.com"
  },
  "plan": {
    "planId": 1,
    "nombre": "FREE",
    "consumoActual": 0
  },
  "mensaje": "Registro exitoso. Guarda tu API Key: no se mostrará nuevamente."
}
```
> ⚠️ El campo `apiKey` solo aparece en esta respuesta. Guardarlo de inmediato — no hay forma de recuperarlo después. Para obtener uno nuevo hay que usar el endpoint de regeneración.

**Respuesta 409 — Email ya registrado:**
```json
{
  "ok": false,
  "codigo": "CORREO_YA_REGISTRADO",
  "mensaje": "El correo juan@empresa.com ya está registrado.",
  "detalles": null
}
```

**Respuesta 422 — Validación fallida:**
```json
{
  "ok": false,
  "codigo": "CAMPO_REQUERIDO",
  "mensaje": "La contraseña debe tener al menos una mayúscula, debe tener al menos un número",
  "detalles": null
}
```

---

### REQUEST 2 — Login

| Campo | Valor |
|-------|-------|
| Método | `POST` |
| URL | `http://localhost:8080/api/v1/auth/login` |
| Headers | `Content-Type: application/json` |
| Body type | `raw → JSON` |

**Body (raw JSON):**
```json
{
  "email": "juan@empresa.com",
  "password": "MiClave123"
}
```

**Respuesta 200 — Éxito:**
```json
{
  "ok": true,
  "jwt": "eyJhbGciOiJIUzI1NiJ9.eyJ1c2VySWQiOjIsInBsYW5JZCI6MX0.xxxx",
  "apiKey": null,
  "usuario": {
    "usuarioId": 2,
    "nombre": "Juan",
    "apellido": "Pérez",
    "email": "juan@empresa.com"
  },
  "plan": {
    "planId": 1,
    "nombre": "FREE",
    "consumoActual": 3
  },
  "mensaje": null
}
```
> `apiKey` es `null` en el login — el hash BCrypt es one-way. Si se necesita el valor plano del API Key hay que regenerarlo desde `POST /usuario/api-key/regenerar`.

**Respuesta 401 — Credenciales incorrectas:**
```json
{
  "ok": false,
  "codigo": "CREDENCIALES_INVALIDAS",
  "mensaje": "Credenciales inválidas.",
  "detalles": null
}
```

**Respuesta 403 — Usuario desactivado:**
```json
{
  "ok": false,
  "codigo": "USUARIO_INACTIVO",
  "mensaje": "Tu cuenta está desactivada. Contacta al soporte.",
  "detalles": null
}
```

---

### REQUEST 3 — Consultar persona por DNI (RENIEC)

| Campo | Valor |
|-------|-------|
| Método | `GET` |
| URL | `http://localhost:8080/api/v1/servicios/reniec/dni` |
| Headers | `X-API-Key: a1b2c3d4e5f6g7h8i9j0k1l2m3n4o5p6` |
| Params (Query) | `numero = 72537503` |
| Body | Ninguno |

**Cómo configurar en Postman:**
1. Seleccionar método `GET`.
2. Pegar la URL: `http://localhost:8080/api/v1/servicios/reniec/dni`.
3. Ir a la pestaña `Params` → Key: `numero` | Value: `72537503`.
4. Ir a la pestaña `Headers` → añadir `X-API-Key` con el valor plano del API Key.
5. No añadir Body.

La URL resultante con el param será: `http://localhost:8080/api/v1/servicios/reniec/dni?numero=72537503`

**Respuesta 200 — Éxito:**
```json
{
  "ok": true,
  "codigo": "OPERACION_EXITOSA",
  "mensaje": "Consulta realizada correctamente.",
  "usuarioId": 2,
  "nombreUsuario": "Juan",
  "plan": "FREE",
  "consumoActual": 4,
  "limiteMaximo": 10,
  "apiServicesFuncionId": 1,
  "servicioNombre": "Consulta DNI",
  "servicioCodigo": "RENIEC_DNI",
  "servicioDescripcion": "Consulta de datos por DNI",
  "data": {
    "dni": "72537503",
    "nombres": "NAGHELY VALERIA",
    "apellidoPaterno": "QUEZADA",
    "apellidoMaterno": "BARRIGA",
    "nombreCompleto": "QUEZADA BARRIGA NAGHELY VALERIA"
  }
}
```
> `limiteMaximo` es `null` si el plan es ENTERPRISE (sin límite). `consumoActual` ya incluye este request.

**Respuesta 422 — DNI con formato incorrecto:**
```json
{
  "ok": false,
  "codigo": "CAMPO_INVALIDO",
  "mensaje": "El DNI debe tener exactamente 8 dígitos numéricos.",
  "detalles": null
}
```

**Respuesta 429 — Límite de plan alcanzado:**
```json
{
  "ok": false,
  "codigo": "LIMITE_ALCANZADO",
  "mensaje": "Has alcanzado el límite de consumos de tu plan FREE (10/10). Cambia tu plan para continuar. Para continuar puedes cambiar a: BASIC (100/mes), PRO (1.000/mes) o ENTERPRISE (sin límite).",
  "detalles": {
    "consumoActual": 10,
    "limiteMaximo": 10,
    "plan": "FREE"
  }
}
```

**Respuesta 401 — API Key inválida o ausente:**
```json
{
  "ok": false,
  "codigo": "API_KEY_INVALIDA",
  "mensaje": "API Key inválida, inactiva o expirada.",
  "detalles": null
}
```

**Respuesta 503 — Decolecta no responde:**
```json
{
  "ok": false,
  "codigo": "SERVICIO_NO_DISPONIBLE",
  "mensaje": "El servicio no está disponible en este momento. Intenta más tarde.",
  "detalles": null
}
```

---

### REQUEST 4 — Consultar contribuyente por RUC (SUNAT)

| Campo | Valor |
|-------|-------|
| Método | `POST` |
| URL | `http://localhost:8080/api/v1/servicios/sunat/ruc` |
| Headers | `X-API-Key: a1b2c3d4e5f6g7h8i9j0k1l2m3n4o5p6` · `Content-Type: application/json` |
| Body type | `raw → JSON` |

**Body (raw JSON):**
```json
{
  "ruc": "20100070970"
}
```

> RUC válido: 11 dígitos, debe comenzar con `10` (persona natural) o `20` (empresa).

**Respuesta 200 — Éxito:**
```json
{
  "ok": true,
  "codigo": "OPERACION_EXITOSA",
  "mensaje": "Consulta realizada correctamente.",
  "usuarioId": 2,
  "nombreUsuario": "Juan",
  "plan": "FREE",
  "consumoActual": 5,
  "limiteMaximo": 10,
  "apiServicesFuncionId": 2,
  "servicioNombre": "Consulta RUC",
  "servicioCodigo": "SUNAT_RUC",
  "servicioDescripcion": "Consulta de datos por RUC",
  "data": {
    "ruc": "20100070970",
    "razonSocial": "EMPRESA EJEMPLO SAC",
    "estado": "ACTIVO",
    "condicion": "HABIDO",
    "direccion": "AV. EJEMPLO 123, LIMA"
  }
}
```

**Respuesta 422 — RUC con formato incorrecto:**
```json
{
  "ok": false,
  "codigo": "CAMPO_INVALIDO",
  "mensaje": "El RUC debe tener 11 dígitos y comenzar con 10 o 20.",
  "detalles": null
}
```

---

### REQUEST 5 — Enviar SMS · Modo TEMPLATE — OTP

| Campo | Valor |
|-------|-------|
| Método | `POST` |
| URL | `http://localhost:8080/api/v1/servicios/sms/enviar` |
| Headers | `X-API-Key: a1b2c3d4e5f6g7h8i9j0k1l2m3n4o5p6` · `Content-Type: application/json` |
| Body type | `raw → JSON` |

**Body (raw JSON):**
```json
{
  "to": "+51999888777",
  "template": "OTP",
  "variables": {
    "brand_app_name": "Extech Utilitarios",
    "code": "839201",
    "minutes": "10"
  }
}
```

> El sistema carga `templates/sms/otp.txt` del classpath y sustituye las variables.
> El SMS que recibe el destinatario será:
> `Extech Utilitarios: Tu código de verificación es 839201. Válido por 10 minutos. No compartas este código con nadie.`

**Respuesta 200 — Éxito:**
```json
{
  "ok": true,
  "mensaje": "SMS enviado correctamente",
  "proveedor": "INFOBIP",
  "referencia": "MSG_ID_123456"
}
```

**Respuesta 422 — Template no encontrado en classpath:**
```json
{
  "ok": false,
  "codigo": "CAMPO_INVALIDO",
  "mensaje": "Template no encontrado en el classpath: 'templates/sms/otp.txt'. Verifica que el archivo exista en src/main/resources/templates/sms/.",
  "detalles": null
}
```

---

### REQUEST 6 — Enviar SMS · Modo TEMPLATE — NOTIFICACION

| Campo | Valor |
|-------|-------|
| Método | `POST` |
| URL | `http://localhost:8080/api/v1/servicios/sms/enviar` |
| Headers | `X-API-Key: a1b2c3d4e5f6g7h8i9j0k1l2m3n4o5p6` · `Content-Type: application/json` |
| Body type | `raw → JSON` |

**Body (raw JSON):**
```json
{
  "to": "+51999888777",
  "template": "NOTIFICACION",
  "variables": {
    "brand_app_name": "Extech Utilitarios",
    "mensaje": "Tu pedido #45231 ha sido despachado y llegará en 2 días hábiles."
  }
}
```

**Respuesta 200 — Éxito:**
```json
{
  "ok": true,
  "mensaje": "SMS enviado correctamente",
  "proveedor": "INFOBIP",
  "referencia": "MSG_ID_789012"
}
```

---

### REQUEST 7 — Enviar SMS · Modo INLINE

| Campo | Valor |
|-------|-------|
| Método | `POST` |
| URL | `http://localhost:8080/api/v1/servicios/sms/enviar` |
| Headers | `X-API-Key: a1b2c3d4e5f6g7h8i9j0k1l2m3n4o5p6` · `Content-Type: application/json` |
| Body type | `raw → JSON` |

**Body (raw JSON):**
```json
{
  "to": "+51999888777",
  "mensaje": "Tu pedido fue confirmado. Número de seguimiento: ORD-2026-001234"
}
```

> Modo INLINE: el campo `mensaje` contiene el texto exacto que se enviará. No se usa ningún template.

**Respuesta 200 — Éxito:**
```json
{
  "ok": true,
  "mensaje": "SMS enviado correctamente",
  "proveedor": "INFOBIP",
  "referencia": "MSG_ID_345678"
}
```

---

### REQUEST 8 — Enviar correo · Modo TEMPLATE — OTP

| Campo | Valor |
|-------|-------|
| Método | `POST` |
| URL | `http://localhost:8080/api/v1/servicios/correo/enviar` |
| Headers | `X-API-Key: a1b2c3d4e5f6g7h8i9j0k1l2m3n4o5p6` · `Content-Type: application/json` |
| Body type | `raw → JSON` |

**Body (raw JSON):**
```json
{
  "to": "destinatario@empresa.com",
  "template": "OTP",
  "variables": {
    "code": "483921",
    "minutes": "10",
    "brand_app_name": "Extech Utilitarios",
    "brand_support_email": "soporte@extech.pe"
  }
}
```

> El sistema carga `templates/correo/otp.html` del classpath, renderiza las variables, y lee el asunto desde `IT_Template.AsuntoTemplate` en BD.

**Respuesta 200 — Éxito:**
```json
{
  "ok": true,
  "mensaje": "Correo enviado correctamente",
  "proveedor": "MICROSOFT_GRAPH",
  "referencia": "msg-id-abc123xyz"
}
```

---

### REQUEST 9 — Enviar correo · Modo TEMPLATE — BIENVENIDA

| Campo | Valor |
|-------|-------|
| Método | `POST` |
| URL | `http://localhost:8080/api/v1/servicios/correo/enviar` |
| Headers | `X-API-Key: a1b2c3d4e5f6g7h8i9j0k1l2m3n4o5p6` · `Content-Type: application/json` |
| Body type | `raw → JSON` |

**Body (raw JSON):**
```json
{
  "to": "nuevousuario@empresa.com",
  "template": "BIENVENIDA",
  "variables": {
    "nombre": "Juan",
    "email": "nuevousuario@empresa.com",
    "brand_app_name": "Extech Utilitarios",
    "brand_support_email": "soporte@extech.pe"
  }
}
```

**Respuesta 200 — Éxito:**
```json
{
  "ok": true,
  "mensaje": "Correo enviado correctamente",
  "proveedor": "MICROSOFT_GRAPH",
  "referencia": "msg-id-bienvenida001"
}
```

---

### REQUEST 10 — Enviar correo · Modo TEMPLATE — LIMITE-ALCANZADO

| Campo | Valor |
|-------|-------|
| Método | `POST` |
| URL | `http://localhost:8080/api/v1/servicios/correo/enviar` |
| Headers | `X-API-Key: a1b2c3d4e5f6g7h8i9j0k1l2m3n4o5p6` · `Content-Type: application/json` |
| Body type | `raw → JSON` |

**Body (raw JSON):**
```json
{
  "to": "juan@empresa.com",
  "template": "LIMITE-ALCANZADO",
  "variables": {
    "nombre": "Juan",
    "plan_actual": "FREE",
    "consumo_actual": "10",
    "link_cambio_plan": "http://localhost:3000/cambiar-plan",
    "brand_app_name": "Extech Utilitarios",
    "brand_support_email": "soporte@extech.pe"
  }
}
```

**Respuesta 200 — Éxito:**
```json
{
  "ok": true,
  "mensaje": "Correo enviado correctamente",
  "proveedor": "MICROSOFT_GRAPH",
  "referencia": "msg-id-limite001"
}
```

---

### REQUEST 11 — Enviar correo · Modo TEMPLATE — CAMBIO-PLAN

| Campo | Valor |
|-------|-------|
| Método | `POST` |
| URL | `http://localhost:8080/api/v1/servicios/correo/enviar` |
| Headers | `X-API-Key: a1b2c3d4e5f6g7h8i9j0k1l2m3n4o5p6` · `Content-Type: application/json` |
| Body type | `raw → JSON` |

**Body (raw JSON):**
```json
{
  "to": "juan@empresa.com",
  "template": "CAMBIO-PLAN",
  "variables": {
    "nombre": "Juan",
    "plan_anterior": "FREE",
    "plan_nuevo": "BASIC",
    "fecha_inicio": "25/03/2026",
    "brand_app_name": "Extech Utilitarios",
    "brand_support_email": "soporte@extech.pe"
  }
}
```

**Respuesta 200 — Éxito:**
```json
{
  "ok": true,
  "mensaje": "Correo enviado correctamente",
  "proveedor": "MICROSOFT_GRAPH",
  "referencia": "msg-id-cambioplan001"
}
```

---

### REQUEST 12 — Enviar correo · Modo TEMPLATE — REGENERACION-API-KEY

| Campo | Valor |
|-------|-------|
| Método | `POST` |
| URL | `http://localhost:8080/api/v1/servicios/correo/enviar` |
| Headers | `X-API-Key: a1b2c3d4e5f6g7h8i9j0k1l2m3n4o5p6` · `Content-Type: application/json` |
| Body type | `raw → JSON` |

**Body (raw JSON):**
```json
{
  "to": "juan@empresa.com",
  "template": "REGENERACION-API-KEY",
  "variables": {
    "nombre": "Juan",
    "fecha_regeneracion": "25/03/2026 14:30",
    "brand_app_name": "Extech Utilitarios",
    "brand_support_email": "soporte@extech.pe"
  }
}
```

**Respuesta 200 — Éxito:**
```json
{
  "ok": true,
  "mensaje": "Correo enviado correctamente",
  "proveedor": "MICROSOFT_GRAPH",
  "referencia": "msg-id-regenkey001"
}
```

---

### REQUEST 13 — Enviar correo · Modo INLINE

| Campo | Valor |
|-------|-------|
| Método | `POST` |
| URL | `http://localhost:8080/api/v1/servicios/correo/enviar` |
| Headers | `X-API-Key: a1b2c3d4e5f6g7h8i9j0k1l2m3n4o5p6` · `Content-Type: application/json` |
| Body type | `raw → JSON` |

**Body (raw JSON):**
```json
{
  "to": "destinatario@empresa.com",
  "asunto": "Notificación del sistema — Extech",
  "cuerpoHtml": "<h2 style='color:#f97316;'>Hola</h2><p>Este es un mensaje directo enviado sin template. Puedes incluir HTML básico.</p><p>Gracias por usar <strong>Extech Utilitarios</strong>.</p>",
  "cuerpoTexto": "Hola. Este es un mensaje directo enviado sin template. Gracias por usar Extech Utilitarios."
}
```

> `cuerpoHtml` es el contenido HTML del correo. `cuerpoTexto` es el fallback para clientes que no renderizan HTML. `asunto` es el asunto del correo (no se lee de BD en modo INLINE).

**Respuesta 200 — Éxito:**
```json
{
  "ok": true,
  "mensaje": "Correo enviado correctamente",
  "proveedor": "MICROSOFT_GRAPH",
  "referencia": "msg-id-inline001"
}
```

---

### REQUEST 14 — Ver perfil del usuario autenticado

| Campo | Valor |
|-------|-------|
| Método | `GET` |
| URL | `http://localhost:8080/api/v1/usuario/perfil` |
| Headers | `X-API-Key: a1b2c3d4e5f6g7h8i9j0k1l2m3n4o5p6` |
| Params | Ninguno |
| Body | Ninguno |

**Respuesta 200 — Éxito:**
```json
{
  "ok": true,
  "usuario": {
    "usuarioId": 2,
    "nombre": "Juan",
    "apellido": "Pérez",
    "email": "juan@empresa.com",
    "telefono": "+51987654321",
    "razonSocial": "Empresa SAC",
    "ruc": "20100070970"
  },
  "plan": {
    "planId": 1,
    "nombre": "FREE",
    "consumoActual": 5,
    "limiteMaximo": 10
  }
}
```

---

### REQUEST 15 — Historial de consumos (paginado)

| Campo | Valor |
|-------|-------|
| Método | `GET` |
| URL | `http://localhost:8080/api/v1/usuario/consumo` |
| Headers | `X-API-Key: a1b2c3d4e5f6g7h8i9j0k1l2m3n4o5p6` |
| Params (Query) | `pageNumber = 1` · `pageSize = 20` |
| Body | Ninguno |

**Cómo configurar en Postman:**
- Pestaña `Params` → añadir dos filas: `pageNumber = 1` y `pageSize = 20`.

**Respuesta 200 — Éxito:**
```json
{
  "ok": true,
  "totalRegistros": 47,
  "pageNumber": 1,
  "pageSize": 20,
  "data": [
    {
      "consumoId": 47,
      "funcion": "Consulta DNI",
      "codigoFuncion": "RENIEC_DNI",
      "exito": true,
      "esConsulta": true,
      "fechaRegistro": "2026-03-25T14:22:10"
    },
    {
      "consumoId": 46,
      "funcion": "Envío SMS",
      "codigoFuncion": "SMS_SEND",
      "exito": true,
      "esConsulta": false,
      "fechaRegistro": "2026-03-25T13:55:02"
    }
  ]
}
```

---

### REQUEST 16 — Resumen de consumo del mes actual

| Campo | Valor |
|-------|-------|
| Método | `GET` |
| URL | `http://localhost:8080/api/v1/usuario/consumo/resumen` |
| Headers | `X-API-Key: a1b2c3d4e5f6g7h8i9j0k1l2m3n4o5p6` |
| Params | Ninguno |
| Body | Ninguno |

**Respuesta 200 — Éxito:**
```json
{
  "ok": true,
  "plan": "FREE",
  "consumoActual": 7,
  "limiteMaximo": 10,
  "porcentajeUso": 70.0
}
```

---

### REQUEST 17 — Cambiar plan de suscripción

| Campo | Valor |
|-------|-------|
| Método | `POST` |
| URL | `http://localhost:8080/api/v1/usuario/cambiar-plan` |
| Headers | `X-API-Key: a1b2c3d4e5f6g7h8i9j0k1l2m3n4o5p6` · `Content-Type: application/json` |
| Body type | `raw → JSON` |

**Body (raw JSON):**
```json
{
  "nuevoPlanId": 2
}
```

| PlanId | Nombre |
|--------|--------|
| 1 | FREE |
| 2 | BASIC |
| 3 | PRO |
| 4 | ENTERPRISE |

**Respuesta 200 — Éxito:**
```json
{
  "ok": true,
  "mensaje": "Plan actualizado correctamente.",
  "plan": {
    "planId": 2,
    "nombre": "BASIC",
    "fechaInicio": "2026-03-25T14:30:00"
  }
}
```

**Respuesta 404 — Plan no existe:**
```json
{
  "ok": false,
  "codigo": "PLAN_NO_ENCONTRADO",
  "mensaje": "El plan especificado no existe o no está activo.",
  "detalles": null
}
```

---

### REQUEST 18 — Regenerar API Key

| Campo | Valor |
|-------|-------|
| Método | `POST` |
| URL | `http://localhost:8080/api/v1/usuario/api-key/regenerar` |
| Headers | `X-API-Key: a1b2c3d4e5f6g7h8i9j0k1l2m3n4o5p6` |
| Body | Ninguno |

> ⚠️ Al ejecutar este request, el API Key actual queda **inválido de inmediato**. El nuevo valor plano que retorna esta respuesta es la única oportunidad de guardarlo.

**Respuesta 200 — Éxito:**
```json
{
  "ok": true,
  "apiKey": "nuevaclave0123456789abcdef01234567",
  "mensaje": "API Key regenerada correctamente. Guárdala ahora: no se mostrará nuevamente.",
  "fechaVigencia": "2027-03-25T14:30:00"
}
```

---

### REQUEST 19 — Admin: listar todos los usuarios

| Campo | Valor |
|-------|-------|
| Método | `GET` |
| URL | `http://localhost:8080/api/v1/admin/usuarios` |
| Headers | `X-API-Key: a1b2c3d4e5f6g7h8i9j0k1l2m3n4o5p6` (de un usuario admin) |
| Params | Ninguno |
| Body | Ninguno |

> Solo funciona si el email del usuario autenticado está en la lista `extech.admin.emails` de `application.properties`.

**Respuesta 200 — Éxito:**
```json
{
  "ok": true,
  "data": [
    {
      "usuarioId": 1,
      "nombre": "Admin",
      "apellido": "Extech",
      "email": "admin@extech.pe",
      "activo": true,
      "plan": "ENTERPRISE"
    },
    {
      "usuarioId": 2,
      "nombre": "Juan",
      "apellido": "Pérez",
      "email": "juan@empresa.com",
      "activo": true,
      "plan": "FREE"
    }
  ]
}
```

**Respuesta 403 — No es admin:**
```json
{
  "ok": false,
  "codigo": "ACCESO_DENEGADO",
  "mensaje": "No tienes permiso para acceder a este recurso.",
  "detalles": null
}
```

---

### REQUEST 20 — Admin: activar o desactivar usuario

| Campo | Valor |
|-------|-------|
| Método | `PUT` |
| URL activar | `http://localhost:8080/api/v1/admin/usuarios/2/activar` |
| URL desactivar | `http://localhost:8080/api/v1/admin/usuarios/2/desactivar` |
| Headers | `X-API-Key: a1b2c3d4e5f6g7h8i9j0k1l2m3n4o5p6` (admin) |
| Body | Ninguno |

**Respuesta 200 — Éxito (activar):**
```json
{
  "ok": true,
  "mensaje": "Usuario activado correctamente.",
  "usuarioId": 2,
  "activo": true
}
```

---

### REQUEST 21 — Admin: listar planes

| Campo | Valor |
|-------|-------|
| Método | `GET` |
| URL | `http://localhost:8080/api/v1/admin/planes` |
| Headers | `X-API-Key: a1b2c3d4e5f6g7h8i9j0k1l2m3n4o5p6` (admin) |
| Body | Ninguno |

**Respuesta 200 — Éxito:**
```json
{
  "ok": true,
  "data": [
    { "planId": 1, "nombre": "FREE",       "precioMensual": 0.00,   "activo": true },
    { "planId": 2, "nombre": "BASIC",      "precioMensual": 29.90,  "activo": true },
    { "planId": 3, "nombre": "PRO",        "precioMensual": 99.90,  "activo": true },
    { "planId": 4, "nombre": "ENTERPRISE", "precioMensual": 499.90, "activo": true }
  ]
}
```

---

### Tabla resumen — todos los endpoints

| # | Método | Endpoint | Headers | Body |
|---|--------|----------|---------|------|
| 1 | POST | `/auth/registro` | Content-Type | JSON con datos de usuario |
| 2 | POST | `/auth/login` | Content-Type | JSON email+password |
| 3 | GET | `/servicios/reniec/dni?numero=` | X-API-Key | Ninguno |
| 4 | POST | `/servicios/sunat/ruc` | X-API-Key, Content-Type | JSON con ruc |
| 5-7 | POST | `/servicios/sms/enviar` | X-API-Key, Content-Type | JSON con template o mensaje |
| 8-13 | POST | `/servicios/correo/enviar` | X-API-Key, Content-Type | JSON con template o asunto+cuerpo |
| 14 | GET | `/usuario/perfil` | X-API-Key | Ninguno |
| 15 | GET | `/usuario/consumo?pageNumber=&pageSize=` | X-API-Key | Ninguno |
| 16 | GET | `/usuario/consumo/resumen` | X-API-Key | Ninguno |
| 17 | POST | `/usuario/cambiar-plan` | X-API-Key, Content-Type | JSON con nuevoPlanId |
| 18 | POST | `/usuario/api-key/regenerar` | X-API-Key | Ninguno |
| 19 | GET | `/admin/usuarios` | X-API-Key (admin) | Ninguno |
| 20 | PUT | `/admin/usuarios/{id}/activar` o `/desactivar` | X-API-Key (admin) | Ninguno |
| 21 | GET | `/admin/planes` | X-API-Key (admin) | Ninguno |

---

## 12. Catálogo de mensajes y errores

### Estructura de respuesta de error

```json
{
  "ok": false,
  "codigo": "CODIGO_ERROR",
  "mensaje": "Mensaje legible para el usuario.",
  "detalles": {
    "campo": "valor"
  }
}
```

### Catálogo completo

| HTTP | Código | Mensaje al usuario | Causa técnica |
|------|--------|--------------------|---------------|
| 400 | `JSON_INVALIDO` | "El cuerpo de la solicitud tiene un formato JSON inválido. Verifica la sintaxis." | `HttpMessageNotReadableException` — JSON malformado |
| 401 | `JWT_REQUERIDO` | "Autenticación requerida. Incluye un JWT válido en el header Authorization." | JWT ausente en endpoint protegido |
| 401 | `JWT_INVALIDO` | "El JWT es inválido o ha expirado. Inicia sesión nuevamente." | `JwtException` en `JwtFilter` |
| 401 | `JWT_EXPIRADO` | "El JWT ha expirado. Inicia sesión nuevamente." | `ExpiredJwtException` |
| 401 | `CREDENCIALES_INVALIDAS` | "Credenciales inválidas." | Email o contraseña incorrectos en login |
| 401 | `API_KEY_INVALIDA` | "API Key inválida, inactiva o expirada." | `ApiKeyInvalidaException` desde `ApiKeyFilter` |
| 403 | `ACCESO_DENEGADO` | "No tienes permiso para acceder a este recurso." | Rol insuficiente (CLIENTE accediendo a /admin) |
| 403 | `USUARIO_INACTIVO` | "Tu cuenta está desactivada. Contacta al soporte." | `UsuarioInactivoException` |
| 404 | `RECURSO_NO_ENCONTRADO` | "El endpoint solicitado no existe. Verifica la URL." | `NoResourceFoundException` |
| 405 | `METODO_NO_PERMITIDO` | "El método HTTP no está permitido para este endpoint." | `HttpRequestMethodNotSupportedException` |
| 409 | `CORREO_YA_REGISTRADO` | "El correo {email} ya está registrado." | Email duplicado en registro |
| 422 | `CAMPO_REQUERIDO` | "{lista de campos inválidos}" | `MethodArgumentNotValidException` o `MissingServletRequestParameterException` |
| 422 | `CAMPO_INVALIDO` | Mensaje específico del validador | `IllegalArgumentException` desde `ValidadorUtil` |
| 422 | `DNI_INVALIDO` | "El DNI debe tener exactamente 8 dígitos numéricos." | DNI no cumple formato |
| 422 | `RUC_INVALIDO` | "El RUC debe tener 11 dígitos y comenzar con 10 o 20." | RUC no cumple formato |
| 429 | `LIMITE_ALCANZADO` | "Has alcanzado el límite de consumos de tu plan {PLAN} ({actual}/{limite}). {opciones_upgrade}" | `LimiteAlcanzadoException` |
| 502 | `PROVEEDOR_ERROR` | "El proveedor externo devolvió un error. Contacta a soporte en soporte@extech.pe." | `ProveedorExternoException` — error HTTP de Decolecta/Infobip |
| 503 | `SERVICIO_NO_DISPONIBLE` | "El servicio no está disponible en este momento. Intenta más tarde." | `ServicioNoDisponibleException` — timeout o conexión fallida |
| 500 | `ERROR_INTERNO` | "Error inesperado del servidor. Si persiste, contacta a soporte en soporte@extech.pe." | `Exception` genérica no capturada |

### Mensajes de LIMITE_ALCANZADO con opciones de upgrade

```
Plan FREE:
"Has alcanzado el límite de consumos de tu plan FREE (10/10). Cambia tu plan para continuar.
Para continuar puedes cambiar a: BASIC (100/mes), PRO (1.000/mes) o ENTERPRISE (sin límite)."

Plan BASIC:
"Has alcanzado el límite de consumos de tu plan BASIC (100/100). Cambia tu plan para continuar.
Para continuar puedes cambiar a: PRO (1.000/mes) o ENTERPRISE (sin límite)."

Plan PRO:
"Has alcanzado el límite de consumos de tu plan PRO (1000/1000). Cambia tu plan para continuar.
Para continuar puedes cambiar a ENTERPRISE (sin límite)."
```

Los `detalles` del error incluyen:
```json
{
  "consumoActual": 10,
  "limiteMaximo": 10,
  "plan": "FREE"
}
```

---

## 13. Estado final del proyecto

### Resumen del estado de todos los archivos

#### Módulo `auth/`
- `AuthController.java` — ✅ Completo. `POST /auth/registro`, `POST /auth/login`.
- `AuthService.java` — ✅ Completo. Registro con API Key one-time, login con JWT. El API Key no se retorna en login.
- `AuthRepository.java` — ✅ Completo. SP: `uspUsuarioValidarAcceso`, `uspPlanObtenerConfiguracionCompleta`.
- `UsuarioController.java` — ✅ Completo. Endpoints `/usuario/**`.
- `UsuarioService.java` — ✅ Completo. Perfil, cambio de plan, regeneración de API Key.
- DTOs (`LoginRequest`, `RegistroRequest`, `AuthResponse`) — ✅ Completos con validaciones.

#### Módulo `reniec/`
- `ReniecService.java` — ✅ Completo. Flujo completo con AES fallback, mapeo de campos Decolecta, logging diagnóstico de errores.
- `ReniecController.java` — ✅ Completo. `GET /servicios/reniec/dni?numero=`. Solo `@SecurityRequirement(name = "apiKeyAuth")`.
- `ReniecRepository.java` — ✅ Completo. SP `uspResolverApiExternaPorUsuarioYFuncion`.
- `ReniecRequest.java` y `ReniecResponse.java` — ✅ Completos con `@JsonInclude(NON_NULL)` y campo `nombreUsuario`.

#### Módulo `sunat/`
- Estado idéntico a `reniec/`. ✅ Completo.

#### Módulo `sms/`
- `SmsService.java` — ✅ Completo. Extiende `EnvioBaseService`. Resolución de nombre, carga de template desde classpath, envío a Infobip, registro de consumo con `EsConsulta=false`.
- `SmsController.java` — ✅ Completo. Solo `@SecurityRequirement(name = "apiKeyAuth")`.
- DTOs con soporte de modo TEMPLATE e INLINE.

#### Módulo `correo/`
- `CorreoService.java` — ✅ Completo. Extiende `EnvioBaseService`. Microsoft Graph (o SMTP) como proveedor.
- `CorreoController.java` — ✅ Completo. Solo `@SecurityRequirement(name = "apiKeyAuth")`.
- DTOs con soporte de modo TEMPLATE e INLINE.

#### Módulo `security/`
- `JwtUtil.java` — ✅ Claims mínimos: `userId`, `planId`. JJWT 0.12+. Métodos: `generar`, `validar`, `extraerUserId`, `extraerPlanId`, `extraerEmail`, `esValido`.
- `JwtFilter.java` — ✅ Extrae JWT, valida, establece SecurityContext con `userId` como atributo del request.
- `ApiKeyUtil.java` — ✅ Generación UUID 32 chars, BCrypt hash, verificación, resolución de `usuarioId` por recorrido de tokens activos.
- `ApiKeyFilter.java` — ✅ Cross-check: verifica que el `usuarioId` del API Key coincida con el `userId` del JWT.

#### Módulo `config/`
- `SecurityConfig.java` — ✅ Stateless, filtros en orden correcto, endpoints públicos: `/api/v1/auth/**`, `/swagger-ui/**`, `/v3/api-docs/**`.
- `SwaggerConfig.java` — ✅ Solo `apiKeyAuth`. Sin `bearerAuth`. Descripción actualizada.
- `CorsConfig.java` — ✅ CORS configurado para frontend React.
- `PasswordEncoderConfig.java` — ✅ `@Bean BCryptPasswordEncoder`.

#### Módulo `domain/`
- `ConsumoRepository.java` — ✅ 7-param `registrar()` con `nombreUsuario VARCHAR(200)`, 6-param delegado, `validarLimitePlan()`, `obtenerTotalMensual()`, `obtenerHistorial()`.
- `UsuarioRepository.java` — ✅ `guardarOActualizar()`, `obtenerNombrePorId()`.
- `TokenRepository.java` — ✅ `insertar()`, `obtenerActivo()`, `desactivarYCrear()`.
- `PlanRepository.java` — ✅ Listar planes activos.

#### Módulo `util/`
- `EnvioBaseService.java` — ✅ `resolverContenido()` carga desde classpath. `resolverAsunto()` lee BD. Sin importación de ArrayList (eliminada).
- `PlantillaUtil.java` — ✅ `renderizar()` y `cargarDesdeClasspath()` con `ClassPathResource`.
- `ValidadorUtil.java` — ✅ `validarDni()`, `validarRuc()`, `esTelefonoValido()`, `bit()` (helper para tipos SQL Server BIT).
- `AesUtil.java` — ✅ AES-256-CBC + `descifrarConFallback()` para compatibilidad con tokens legacy.
- `PlanContext.java` — ✅ Record: `plan`, `consumoActual`, `limiteMaximo` (nullable).

#### Módulo `exception/`
- `GlobalExceptionHandler.java` — ✅ Maneja 12 tipos de excepción. Mensaje `LIMITE_ALCANZADO` con opciones de upgrade por plan. Sin detalles técnicos expuestos al cliente.
- `LimiteAlcanzadoException.java` — ✅ Incluye `consumoActual`, `limiteMaximo`, `plan` como detalles.
- `ApiKeyInvalidaException.java`, `UsuarioInactivoException.java`, `ProveedorExternoException.java`, `ServicioNoDisponibleException.java` — ✅ Todas completas.
- `ErrorResponse.java` — ✅ DTO: `ok=false`, `codigo`, `mensaje`, `detalles` (Map nullable).

#### Templates HTML (`templates/correo/`)
- `otp.html` — ✅ Con logo Extech, código OTP destacado, aviso de seguridad.
- `bienvenida.html` — ✅ Con logo Extech, correo de acceso visible.
- `limite-alcanzado.html` — ✅ Con logo Extech, botón CTA "Cambiar mi plan".
- `cambio-plan.html` — ✅ Con logo Extech, detalles del plan anterior y nuevo.
- `regeneracion-api-key.html` — ✅ Con logo Extech, aviso de invalidación del anterior.
- `otp.txt` — ✅ Fallback texto plano para OTP.

#### Templates SMS (`templates/sms/`)
- `otp.txt` — ✅ Formato compacto con variables brand, code, minutes.
- `notificacion.txt` — ✅ Template genérico de notificación.

#### Scripts SQL (`sql/`)
- `insert_limites_todos_planes.sql` — ✅ Inserts idempotentes para FREE(10), BASIC(100), PRO(1000). ENTERPRISE sin inserts.
- `insert_limites_sms_correo.sql` — ✅ Complemento específico para funciones SMS_SEND y CORREO_ENVIO.
- `alter_sp_consumo_registrar_varchar.sql` — ✅ Cambia `@UsuarioRegistro` de INT a VARCHAR(200).
- `fix_uspApiKeyDesactivarYCrear.sql` — ✅ Corrección del SP de regeneración.
- `insert_template_correo_otp.sql` — ✅ Inserta registro en IT_Template para OTP.
- `update_template_correo_otp_v2.sql` — ✅ Actualiza CuerpoTemplate a referencia de classpath.
- `fix_decolecta_reniec_auth.sql` — ✅ Corrección de IT_ApiExternaFuncion.Autorizacion.
- `update_decolecta_token.sql` — ✅ Actualización del token de Decolecta.
- `check_decolecta_token_status.sql` — ✅ Verificación del estado del token.

### Decisiones de diseño relevantes

1. **`IT_Consumo.UsuarioRegistro` almacena el nombre** — no el ID ni el email. Permite leer la tabla históricamente sin JOINs adicionales.

2. **Templates en classpath, no en BD** — el HTML vive en Git. Solo `AsuntoTemplate` (asunto del correo) permanece en BD para permitir cambios sin redeploy.

3. **ENTERPRISE sin filas en `IT_PlanFuncionLimite`** — la ausencia de registro es el mecanismo para "sin límite". No se usa un número artificialmente alto. El SP maneja NULL como "libre".

4. **`bearerAuth` eliminado de Swagger** — el JWT es un detalle interno de seguridad que el consumidor de la API no necesita configurar en Swagger. Solo `apiKeyAuth` es relevante para probar los endpoints de servicios.

5. **`AesUtil.descifrarConFallback()`** — permite convivir con tokens legados en texto plano en BD mientras se migran gradualmente a AES-256.

6. **`ValidadorUtil.bit(Object)`** — el driver MS JDBC puede retornar tipos BIT como `Boolean`, `Integer` (1/0) o `Short`. El método unifica el manejo para evitar `ClassCastException`.

7. **`PlanContext.consumoActual + 1`** — el SP valida ANTES de registrar el consumo actual. La respuesta al cliente debe mostrar `consumoActual + 1` para reflejar que este request ya fue contado.

8. **JWT stateless, sin renovación automática** — el usuario hace login para obtener un nuevo JWT. Duración: 1 hora. Si el plan cambia, el JWT anterior sigue siendo válido con el `planId` anterior hasta que expire.

9. **Resolución del nombre del usuario al inicio de cada servicio** — disponible en todos los paths de error para que `IT_Consumo.UsuarioRegistro` siempre tenga el nombre correcto, incluso en requests fallidos.

10. **`GlobalExceptionHandler` maneja 404 con `NoResourceFoundException`** — requiere configuración adicional en `application.properties`:
    ```properties
    spring.mvc.throw-exception-if-no-handler-found=true
    spring.web.resources.add-mappings=false
    ```

### Variables de entorno requeridas para producción

| Variable | Descripción |
|----------|-------------|
| `JWT_SECRET` | Clave secreta HS256, mínimo 256 bits |
| `AES_KEY` | Clave AES-256 para tokens de proveedores externos |
| `SMTP_USER` | Usuario SMTP (si usa SMTP en lugar de Microsoft Graph) |
| `SMTP_PASSWORD` | Contraseña SMTP |
| `AZURE_CLIENT_ID` | Client ID de la aplicación Azure (para Microsoft Graph) |
| `AZURE_CLIENT_SECRET` | Client Secret de Azure |
| `AZURE_TENANT_ID` | Tenant ID de Azure |

> El `application.properties` en el repositorio NO debe contener valores reales. Solo referencias `${VARIABLE}`. El archivo real con credenciales debe estar en `.gitignore`.

### URL de acceso

| Ambiente | URL base |
|----------|---------|
| Desarrollo | `http://localhost:8080/api/v1` |
| Swagger UI | `http://localhost:8080/swagger-ui.html` |
| Producción | `https://api.extech.pe/utilitarios/v1` |

---

*Documentación técnica generada el 25 de marzo de 2026 — Extech Utilitarios v1.0*
*Consultoría Extech · jesus.ruiz@extech.pe*
