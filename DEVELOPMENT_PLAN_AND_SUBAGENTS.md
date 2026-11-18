# Структурный план разработки мобильного клиента БобрДобр VPN

> Документ регламентирует фазы разработки, архитектурные требования, адаптацию фирменного стиля **БобрДобр VPN**, спецификацию сборки в контейнере **Podman** и распределение выделенных **субагентов** на каждый этап.

---

## 1. Фирменный стиль и мобильная дизайн-система БобрДобр VPN

Бренд **«БобрДобр VPN — Центр гидробиологических инноваций»** сохраняет свою уникальную неоново-киберпанковую эстетику, адаптированную под современные принципы мобильного UX (Material You 3, плавные жесты, тактильный отклик Haptic Feedback, поддержка Dynamic Color).

### 1.1 Цветовая палитра (Color Tokens)
Палитра перенесена из оригинального десктопного `index.html` и формализована в Compose Tokens:
- **`BackgroundMain`**: `#0C1316` (Глубокий сине-черный обсидиан)
- **`BackgroundSurface`**: `#10191F` (Поверхности карточек и диалогов)
- **`BackgroundCard`**: `#141F26` (Карточки серверов и настроек)
- **`BackgroundCardActive`**: `#15262C` (Выделенный элемент)
- **`NeonAmber`**: `#F59E0B` / `#FBBF24` (Состояние `Connecting`, ключевой фирменный акцент)
- **`NeonEmerald`**: `#10B981` / `#34D399` (Состояние `Connected`, защищенная плотина активна)
- **`NeonCyan`**: `#38BDF8` / `#06B6D4` (Телеметрия скорости, пинг, трафик RX/TX)
- **`NeonPurple`**: `#C084FC` / `#A855F7` (Подписки и статус тарифа)
- **`NeonRed`**: `#F87171` / `#EF4444` (Ошибка соединения, отключено, разрыв)

### 1.2 Графические ассеты бренда
Все графические ресурсы скопированы в каталог `assets/`:
* `assets/beaver.svg`: Официальный маскот-бобр БобрДобр. Преобразуется в векторный `AdaptiveIcon` Android (foreground + background).
* `assets/amnezia.svg`: Векторная иконка протокола AmneziaWG (янтарный градиент).
* `assets/openconnect.svg`: Векторная иконка AnyConnect/OpenConnect (бирюзовый градиент).
* `assets/subscription.svg`: Иконка личного кабинета / подписки.

### 1.3 Фирменные мобильные UI-компоненты (Jetpack Compose)
1. **BeaverConnectButton**: Центральная кнопка подключения со световым лучом по периметру (анимированный градиент с переходом из янтарного `NeonAmber` в изумрудный `NeonEmerald` при установлении связи).
2. **DamStatusBar**: Верхняя статус-панель ("Плотина защищена" / "Шлюзы открыты").
3. **CyberServerCard**: Карточка сервера с индикатором пинга, флагом локации и бейджем протокола (AmneziaWG / AnyConnect).
4. **HydroTelemetryBar**: Плашка счетчиков RX/TX в реальном времени с неоновыми спарклайнами скорости.

---

## 2. Среда сборки: Podman Container Toolchain

Вся компиляция (Kotlin/Gradle, Rust `cargo-ndk`, Go Android c-shared) изолирована в контейнере Podman. Разработчику на хосте не требуется ставить NDK, Rust или Go локально.

### 2.1 Конфигурация контейнера (`Containerfile`)
- **Базовый образ**: `docker.io/library/ubuntu:22.04` (или `fedora:40`)
- **JDK**: OpenJDK 17 / 21
- **Android SDK**: Command-line tools (API 34, Build-tools 34.0.0)
- **Android NDK**: r26c (26.2.11394342)
- **Rust**: `rustup` с таргетами:
  - `aarch64-linux-android` (ARM64)
  - `armv7-linux-androideabi` (ARMv7)
  - `x86_64-linux-android` (Эмуляторы)
  - Утилита `cargo-ndk`
- **Go**: Go 1.24 с поддержкой `GOOS=android`, `CGO_ENABLED=1`
- **Скрипт запуска**: `podman-build.ps1` (пробрасывает рабочую директорию как том `/workspace` и запускает компиляцию).

---

## 3. Структурный план разработки и матрица субагентов

Разработка разделена на 6 этапов с закреплением **выделенного субагента** за каждой задачей.

```
┌────────────────────────────────────────────────────────────────────────┐
│                   МАТРИЦА СУБАГЕНТОВ РАЗРАБОТКИ                        │
├────────┬────────────────────────────────┬──────────────────────────────┤
│ Этап   │ Задача                         │ Назначенный субагент         │
├────────┼────────────────────────────────┼──────────────────────────────┤
│ Фаза 1 │ Podman Build Environment       │ @agent-podman-infra          │
├────────┼────────────────────────────────┼──────────────────────────────┤
│ Фаза 2 │ Android Shell & VpnService     │ @agent-android-core          │
├────────┼────────────────────────────────┼──────────────────────────────┤
│ Фаза 3 │ Rust L3 & AmneziaWG Engine     │ @agent-rust-engine           │
├────────┼────────────────────────────────┼──────────────────────────────┤
│ Фаза 4 │ Go AnyConnect uTLS Engine      │ @agent-go-worker             │
├────────┼────────────────────────────────┼──────────────────────────────┤
│ Фаза 5 │ Jetpack Compose Brand UI       │ @agent-compose-ui            │
├────────┼────────────────────────────────┼──────────────────────────────┤
│ Фаза 6 │ Split Tunneling & QA Profiling │ @agent-network-qa            │
└────────┴────────────────────────────────┴──────────────────────────────┘
```

---

### Фаза 1: Инфраструктура сборки в Podman
* **Субагент**: `@agent-podman-infra` (DevOps & Android NDK Specialist)
* **Задачи**:
  - Создание `build-container/Containerfile` с полным набором инструментов (Android SDK 34, NDK r26c, Rust cargo-ndk, Go 1.24).
  - Написание скриптов сборки: `build.sh` (внутри контейнера) и `podman-build.ps1` (для запуска на хосте Windows с WSL/Podman machine).
  - Создание скелета Gradle-проекта (`settings.gradle.kts`, `build.gradle.kts`, модули `:app`, `:vpn-core`, `:engine-rust`, `:engine-go`).
* **Результат**: Успешная тестовая сборка пустого APK внутри Podman.

---

### Фаза 2: Нативная Android-служба (`VpnService`) и JNI-мост
* **Субагент**: `@agent-android-core` (Android System & JNI Architect)
* **Задачи**:
  - Реализация `VpnTunnelService : android.net.VpnService` в модуле `:vpn-core`.
  - Управление жизненным циклом Foreground Service (уведомления с кнопкой «Отключить», Android 14+ тип `systemExempted`).
  - Механизм защиты сокетов: метод `protectSocket(int fd)` с вызовом `VpnService.protect(fd)`.
  - Извлечение дескриптора `int fd` через `pfd.detachFd()`.
  - JNI C-ABI интерфейс `engine_api.h` (`start_tunnel`, `stop_tunnel`, `protect_callback`, `status_callback`).
  - Защищенное хранилище на `Android Keystore` + `EncryptedSharedPreferences`.
* **Результат**: Рабочий сервис, поднимающий системный TUN-интерфейс и готовый передавать `fd` в нативные библиотеки.

---

### Фаза 3: Движок Rust (TUN Packet Loop & AmneziaWG)
* **Субагент**: `@agent-rust-engine` (Rust Network & Crypto Systems Engineer)
* **Задачи**:
  - Создание Rust-библиотеки `libengine_rust.so` с экспортом функций JNI.
  - Высокопроизводительный Zero-Allocation цикл чтения/записи `tun_fd` через `mio` / `epoll`.
  - Интеграция протокола AmneziaWG на базе `amneziawg-rs` / `boringtun`:
    - Поддержка параметров обфускации: `Jc`, `Jmin`, `Jmax`, `S1`, `S2`, `H1..H4`.
  - Обратный вызов `protectSocket` перед отправкой первого UDP handshake пакета.
  - Потокобезопасная трансляция метрик трафика RX/TX в Kotlin.
* **Результат**: Скомпилированная `libengine_rust.so`, успешно устанавливающая AmneziaWG-туннель на мобильном устройстве.

---

### Фаза 4: Движок Go (AnyConnect OpenConnect с uTLS)
* **Субагент**: `@agent-go-worker` (Go & uTLS Network Protocols Specialist)
* **Задачи**:
  - Портирование существующего `openconnect-worker/` в разделяемую библиотеку `libengine_go.so` (`-buildmode=c-shared`).
  - Адаптация TUN под Android: чтение/запись через переданный `int fd` (`os.NewFile(uintptr(tunFd), "/dev/tun")`).
  - Интеграция сокетной защиты: вызов `protectSocket` через `net.Dialer.Control(func(fd uintptr))` до TLS рукопожатия.
  - Сохранение функционала uTLS Chrome Auto (`tls.HelloChrome_Auto`), ALPN `http/1.1`, камуфляжа `?secret=...`.
  - Реализация грациозного завершения сессии: отправка CSTP BYE пакета (`0x05`) при вызове `stopGoTunnel`.
* **Результат**: Скомпилированная `libengine_go.so`, поднимающая CSTP/DTLS туннель с маскировкой под Chrome.

---

### Фаза 5: Мобильный UI в фирменном стиле БобрДобр (Jetpack Compose)
* **Субагент**: `@agent-compose-ui` (Android Mobile UI/UX & Compose Animator)
* **Задачи**:
  - Реализация Design System: палитра `NeonAmber`, `NeonEmerald`, `NeonCyan`, `NeonPurple`, шрифты, карточки с неоновым свечением.
  - Разработка векторного значка приложения `ic_launcher` на базе `beaver.svg`.
  - Экран Dashboard: неоновая кнопка подключения с анимацией бегущего светового луча, переключатель серверов, статус "Плотина под защитой".
  - Экран управления подписками и ручной конфигурацией серверов.
  - Монитор трафика в реальном времени с графиками и пингом.
  - Плитка быстрых настроек QuickSettings Tile для включения/выключения VPN из системной шторки Android.
* **Результат**: Полноценный киберпанк интерфейс БобрДобр VPN на чистом Compose.

---

### Фаза 6: Раздельное туннелирование, подписки и оптимизация батареи
* **Субагент**: `@agent-network-qa` (Network Routing & Mobile QA Engineer)
* **Задачи**:
  - Per-App Split Tunneling: экран выбора приложений со считыванием установленных пакетов через `PackageManager`, передача в `addAllowedApplication` / `addDisallowedApplication`.
  - Пре-резолвинг доменов обхода (Bypass Domains) и DNS Leak Protection.
  - Перенос клиента подписок: периодическая фоновая синхронизация через `WorkManager`, обработка HTTP 429 backoff и `is_frozen`.
  - Оптимизация Doze Mode: проверка удержания связи при выключенном экране и переключении Wi-Fi <-> 4G/5G (роуминг без пересоздания TUN `fd`).
* **Результат**: Готовый релизный APK/AAB клиент, проверенный на утечки памяти и стабильность сессии.
