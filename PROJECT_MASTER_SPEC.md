# Спецификация проекта: Мобильный клиент БобрДобр VPN для Android
> **Бренд**: БобрДобр VPN («Центр гидробиологических инноваций»)  
> **Стек**: Rust (L3 packet IO + AmneziaWG) + Go (AnyConnect CSTP/DTLS + uTLS) + Kotlin / Jetpack Compose (UI & VpnService)  
> **Среда сборки**: Podman (Android SDK 34, NDK r26c, Rust cargo-ndk, Go 1.24)  
> **Исходная база**: Windows-клиент v1.3.2 (`C:\Users\Egor\Documents\DevTools_Super_Debug\VPN_Windows_Client`)

---

## 1. Консолидированная информация по анализу Windows-клиента

### 1.1 Архитектура и протоколы
* **AmneziaWG (WireGuard с обфускацией)**:
  - Параметры: `Jc` (число мусорных пакетов), `Jmin`/`Jmax` (диапазон размера мусора), `S1`/`S2` (паддинг первого/второго пакета рукопожатия), `H1`..`H4` (кастомные заголовки типов сообщений).
  - Настройка подсетей: алгоритм `SubtractCIDRs` математически вырезает адреса исключений (Bypass Routes) из диапазона `0.0.0.0/0`, предотвращая перехват трафика файрволом.
* **OpenConnect CSTP/DTLS (Cisco AnyConnect)**:
  - Автономный `openconnect-worker` на чистом Go.
  - **uTLS**: пресет `tls.HelloChrome_Auto`, подмена ALPN на `["http/1.1"]`, поддержка URL-камуфляжа `?secret=...`.
  - Аутентификация: двухшаговый XML-диалог AnyConnect (`init` -> `auth-reply`) с получением сессионного токена или cookie `webvpn`.
  - Транспорт: CSTP over TLS (фрейминг `STF\x01`, 8 байт заголовок) + DTLS over UDP (`pion/dtls/v3`).
  - **Graceful Shutdown**: при выходе отправляется CSTP BYE пакет (`0x05`, "Client disconnect"), что исключает зависание сессий на сервере.
* **Подписки и конфигурация**:
  - Поддержка Remnawave JSON, списков серверов, Base64, сырых `.conf`.
  - Обработка HTTP 429 (`Retry-After`) с экспоненциальным backoff.
  - Контроль статуса `is_frozen`: 0 — активен, 1 — истек, 2 — заблокирован.

### 1.2 Мобильные реалии и отличия от Windows
1. **Процессы**: На Android запрещено запускать сторонние `.exe`/ELF процессы через `exec()`. Вся работа ведется внутри единого процесса через нативные динамические библиотеки (`.so`) по JNI.
2. **TUN устройство**: Вместо WinTun адаптера ОС Android через `VpnService.Builder().establish()` отдает файловый дескриптор `ParcelFileDescriptor` (`int fd`).
3. **Защита от петель**: Вызов `VpnService.protect(int socketFd)` обязателен для каждого сокета (WireGuard UDP, AnyConnect TCP/UDP) **до** вызова `connect()`.
4. **Батарея**: Фоновый сборщик мусора Go и таймеры рантайма будят процессор. Поэтому для непрерывного цикла TUN чтения/записи используется **Rust** (спит в `epoll_wait` без GC), экономя 15–20% заряда аккумулятора.
5. **Шифрование ключей**: Вместо Windows DPAPI используется **Android Keystore** + аппаратный чип TEE/StrongBox.

---

## 2. Разделение обязанностей: Rust vs Go

| Компонент | Стек | Роль и реализация |
|---|---|---|
| **TUN Packet IO & AmneziaWG** | **Rust** | Zero-allocation чтение/запись `tun_fd` через `mio`/`epoll`, криптография ARM NEON, обработка мусорных пакетов `Jc/S1/S2/H1..H4`. |
| **AnyConnect CSTP/DTLS + uTLS** | **Go** | Браузерная маскировка под Chrome через `uTLS`, AnyConnect XML авторизация, поддержка камуфляжа токенов, CSTP/DTLS туннелирование. |
| **Android Shell & UI** | **Kotlin (Compose)** | `VpnService`, жизненный цикл Foreground Service, защита сокетов, Per-App Split Tunneling, киберпанк UI БобрДобр. |

---

## 3. Архитектурная модель: Pluggable Native Engines

```
Kotlin VpnTunnelService (Android VpnService)
   ├── establish() -> int tun_fd
   ├── protectSocket(int fd) -> Binder netd (SO_MARK)
   │
   ├── [Профиль AmneziaWG] ──> libengine_rust.so (JNI: Rust Engine)
   │                             ├── Zero-alloc TUN Loop
   │                             └── AmneziaWG / WireGuard crypto
   │
   └── [Профиль OpenConnect] ─> libengine_go.so   (JNI: Go Engine)
                                 ├── uTLS Chrome Handshake
                                 └── CSTP / DTLS AnyConnect tunnel
```
