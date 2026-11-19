# Vintage Manager

App Android **100 % nativa** para administrar una tienda de ropa vintage / de segunda mano en Chile:
finanzas, impuestos (IVA, PPM, F29, Renta), inventario con rotación FEFO y alertas que no saturan
la barra de notificaciones. Todo funciona sin internet.

```
┌──────────────────────────────────────────────┐
│  Capa Android (Kotlin)                       │
│  Jetpack Compose · ViewModels · WorkManager  │
│  Notificaciones · Cámara · Permisos          │
└──────────────────┬───────────────────────────┘
                   │ bindings generados con UniFFI (JNA)
┌──────────────────▼───────────────────────────┐
│  vintage_core (Rust → libvintage_core.so)    │
│  Dominio · SQLite · Motor tributario · FEFO  │
│  Alertas · Reportes · Respaldo JSON          │
└──────────────────────────────────────────────┘
```

**Toda la lógica de negocio vive en Rust.** Kotlin no recalcula IVA, PPM, FLR ni prioridades:
solo muestra lo que devuelve el núcleo.

## Estructura

```
vintage-manager/
├── vintage_core/                 # Crate Rust (cdylib)
│   ├── Cargo.toml                # perfil release: opt-level 3, LTO, panic=abort, strip
│   ├── uniffi.toml               # NaiveDate → LocalDate, Decimal → BigDecimal
│   ├── uniffi-bindgen.rs         # generador de bindings (feature "cli")
│   └── src/
│       ├── lib.rs                # objeto VintageCore exportado (API UniFFI)
│       ├── models.rs             # modelo de dominio (Records / Enums)
│       ├── db.rs                 # esquema SQLite + migraciones (rusqlite_migration)
│       ├── finance.rs            # movimientos, cobros, pagos, proveedores
│       ├── tax.rs                # motor tributario (IVA, PPM, F29, Renta)
│       ├── inventory_fefo.rs     # Fecha Límite de Rotación, semáforo, lotes
│       ├── alerts.rs             # reglas, prioridad, dedup, tope diario
│       ├── reports.rs            # Resumen, gráfico, métricas, CSV
│       ├── backup.rs             # respaldo / restauración JSON
│       ├── settings.rs           # ajustes (todas las tasas editables)
│       ├── demo.rs               # datos de demostración
│       ├── sync.rs               # trait SyncBackend (futuro, sin implementar)
│       ├── error.rs              # CoreError → CoreException en Kotlin
│       └── types.rs              # tipos personalizados UniFFI
├── app/                          # App Android (Kotlin + Compose + Hilt)
│   ├── build.gradle.kts          # tareas cargoNdkBuild y generateUniffiBindings
│   └── src/main/java/cl/vintagemanager/
│       ├── VintageApp.kt, MainActivity.kt
│       ├── di/                   # VintageCore como singleton (Hilt)
│       ├── data/                 # VintageRepository (envoltorio fino)
│       ├── notifications/        # Worker diario, canales, acciones
│       ├── export/               # PDF y CSV para el contador
│       └── ui/                   # Resumen · Finanzas · Inventario · Ajustes
└── .github/workflows/android.yml # CI: cargo test + assembleRelease + test en emulador
```

## Compilar paso a paso

### 1. Requisitos

| Herramienta | Versión |
|---|---|
| JDK | 17 |
| Android SDK | plataforma 35 (Android Studio Ladybug o superior) |
| Android NDK | 27.2.12479018 |
| Rust | estable (1.79 o superior) |
| cargo-ndk | 3.x |

### 2. Instalar Rust y los targets de Android

```bash
curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh
```

```bash
rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android
```

### 3. Instalar cargo-ndk

```bash
cargo install cargo-ndk --locked
```

### 4. Instalar el NDK

Desde Android Studio: *Settings → Languages & Frameworks → Android SDK → SDK Tools → NDK (Side by side)*,
o por línea de comandos:

```bash
sdkmanager --install "ndk;27.2.12479018"
```

El build encuentra el NDK en `ANDROID_NDK_HOME` o en `<sdk>/ndk/27.2.12479018`
(el SDK se lee de `local.properties` o de `ANDROID_HOME`).

### 5. Probar el núcleo Rust

```bash
cd vintage_core && cargo test
```

Incluye pruebas del motor tributario (remanente de IVA arrastrado, boletas sin crédito, PPM,
renta), del motor FEFO (cambio de temporada, materiales delicados, rebaja bajo costo, pausa por
reparación, prorrateo de lotes) y de alertas (tope de 3 notificaciones, deduplicación 24 h,
escalamiento de prioridad, horario silencioso, datos de demostración).

### 6. Compilar la app (Rust + Kotlin en un solo comando)

```bash
./gradlew assembleRelease
```

Antes de compilar Kotlin, Gradle ejecuta automáticamente:

1. `cargoNdkBuild` → `cargo ndk -t arm64-v8a -t armeabi-v7a -t x86_64 build --release` y copia
   `libvintage_core.so` a `app/src/main/jniLibs/<abi>/`.
2. `cargoHostBuild` + `generateUniffiBindings` → genera `vintage_core.kt` en
   `app/build/generated/uniffi/kotlin` (nada de JNI escrito a mano).

El APK queda en `app/build/outputs/apk/release/app-release.apk` (firmado con la clave de
depuración para instalarlo directamente; para Google Play configura una clave propia).

### 7. Ejecutar

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
```

O ábrelo en Android Studio y ejecuta en un emulador Android 13+ (x86_64) o en un teléfono (arm64).
En **Ajustes → Datos de demostración** puedes cargar 40 prendas, 3 meses de movimientos, cobros,
pagos y lotes.

Test instrumentado (verifica que la librería nativa carga y responde):

```bash
./gradlew connectedDebugAndroidTest
```

## Motor tributario (Chile)

Todas las tasas son **parámetros editables en Ajustes** (valores por defecto año comercial 2026):
IVA 19 %; Pro Pyme General IDPC 12,5 % y PPM 0,125 % / 0,25 % (umbral 50.000 UF);
Pro Pyme Transparente IDPC 0 % y PPM 0,2 % / 0,5 %; Régimen General IDPC 27 % y PPM manual.

- **IVA** con IVA incluido: `neto = round(bruto / (1 + tasa))`, `iva = bruto − neto`.
- **Débito**: ventas con boleta o factura. **Crédito**: solo compras con factura.
- **IVA a pagar** = débito − crédito − remanente anterior; si es negativo se arrastra.
- **PPM** = ingresos netos del mes × tasa. **Total F29** = IVA a pagar + PPM.
- **Redondeo**: a peso entero, mitad hacia arriba (`$0,5 → $1`).
- Vencimiento F29 el día 20 (o 12), corrido al lunes si cae en fin de semana.

> Cálculo referencial. Confirma con tu contador antes de declarar en el SII.

## Alertas sin saturación

Un Worker diario (09:00 por defecto) llama a `evaluate_alerts(today, minute)` del núcleo, que
aplica prioridades, deduplicación de 24 h (salvo que la prioridad suba), horario silencioso
(21:00–08:30), agrupación del inventario y el **tope de 3 notificaciones diarias** (si hay más,
se combinan en un resumen InboxStyle). Solo CRÍTICA y ALTA llegan como push; el resto se ve en
la app. Canales: `alertas_criticas` y `alertas_resumen`.
