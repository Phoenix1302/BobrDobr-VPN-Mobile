# БобрДобр VPN — Android Mobile Client

[![Release](https://img.shields.io/github/v/release/Phoenix1302/BobrDobr-VPN-Mobile?color=10B981&label=Release)](https://github.com/Phoenix1302/BobrDobr-VPN-Mobile/releases)
[![Android](https://img.shields.io/badge/Android-8.0%2B%20%28API%2026--34%29-38BDF8)](https://developer.android.com)
[![16KB Page Size](https://img.shields.io/badge/Page%20Size-16%20KB%20Aligned%20%28Android%2015%2B%29-F59E0B)](https://developer.android.com/guide/practices/page-sizes)
[![License](https://img.shields.io/badge/License-Proprietary-8B5CF6)](#)

> **Бренд**: БобрДобр VPN («Центр гидробиологических инноваций»)  
> Высокопроизводительный киберпанк VPN-клиент для Android с поддержкой протоколов AmneziaWG и AnyConnect (OpenConnect) uTLS.

---

## 🚀 Архитектура и технологии

Приложение построено на базе гибридного модульного мульти-стека для максимальной скорости, низкого потребления батареи и обхода DPI-блокировок:

```
Kotlin VpnTunnelService (Android VpnService)
   ├── establish() -> int tun_fd
   ├── protectSocket(int fd) -> Binder netd (SO_MARK)
   │
   ├── [Профиль AmneziaWG] ──> libengine_rust.so (JNI: Rust Engine)
   │                             ├── Zero-alloc TUN Loop (mio/epoll)
   │                             └── AmneziaWG / WireGuard crypto + Jc/S1/S2/H1..H4
   │
   └── [Профиль AnyConnect] ─> libengine_go.so   (JNI: Go Engine)
                                 ├── uTLS Chrome Auto Handshake (маскировка под браузер)
                                 └── CSTP / DTLS AnyConnect tunnel + URL Secret
```

### 1. Rust Engine (`:engine-rust`)
- **Zero-allocation чтение/запись** в `tun_fd` через асинхронный цикл `mio` / `epoll`.
- Полная реализация протокола **AmneziaWG** (WireGuard с мусорными пакетами `Jc`, диапазоном `Jmin`/`Jmax`, паддингами `S1`/`S2` и кастомными заголовками `H1`..`H4`).
- Экономия 15–20% заряда аккумулятора по сравнению с рантаймом Go за счет отсутствия GC в цикле пересылки пакетов.

### 2. Go Engine (`:engine-go`)
- **OpenConnect / Cisco AnyConnect** воркер с поддержкой CSTP over TLS и DTLS over UDP.
- **uTLS fingerprinting**: точная имитация TLS Client Hello браузера Chrome (`HelloChrome_Auto`), подмена ALPN на `["http/1.1"]`.
- Поддержка камуфляжа `?secret=...` и XML-аутентификации AnyConnect.
- Корректный **Graceful Shutdown** с отправкой пакета CSTP BYE (`0x05`).

### 3. C++ JNI Bridge & Core (`:vpn-core`)
- Безопасная передача файловых дескрипторов `tun_fd` и сокетов без утечек.
- Защита сокетов через `VpnService.protect()` до вызова `connect()`.
- Скомпилирован с `-DANDROID_STL=c++_static` и флагами выравнивания страниц **16 KB** (`-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384`), полностью совместим с Android 15 (Pixel 8 / arm64-v8a).

### 4. UI & Shell (`:app`)
- **Jetpack Compose + Material 3** в фирменной эстетике БобрДобр (цветовые токены `BackgroundMain #0C1316`, `NeonAmber`, `NeonEmerald`, `NeonCyan`, `NeonPurple`).
- **Интерактивная кнопка плотины** с анимированным градиентным световым лучом.
- **Per-App Split Tunneling**: раздельное туннелирование трафика с выбором установленных приложений.
- **Встроенный QR-сканер**: быстрое добавление конфигураций AmneziaWG через камеру.
- **Телеметрия**: измерение пинга до шлюза внутри активного туннеля, счетчики трафика RX/TX в реальном времени.
- **Авто-обновление**: проверка и скачивание новых релизов напрямую из GitHub Releases.

---

## 🛠 Сборка в контейнере Podman

Все зависимости (Android SDK 34, NDK r26c, Rust cargo-ndk, Go 1.24, Gradle 8.8) упакованы в контейнер:

```powershell
# Сборка релизного APK
powershell -ExecutionPolicy Bypass -File .\podman-build.ps1 -Task "assembleRelease"

# Сборка debug APK
powershell -ExecutionPolicy Bypass -File .\podman-build.ps1 -Task "assembleDebug"
```

Собранный релизный APK помещается в:
`app/build/outputs/apk/release/app-release.apk`

---

## 📱 Системные требования

- **Android 8.0 (API 26)** или выше (до **Android 15 / API 34+** включительно).
- Архитектуры процессоров: **ARM64-v8a**, **ARMv7-a**, **x86_64**.
- Полное соответствие требованиям **Android 15 16KB Page Size Alignment**.
