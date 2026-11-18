# Промпт для старта нового диалога разработки

Скопируйте следующий текст в первое сообщение нового диалога в Antigravity:

```markdown
Мы начинаем разработку мобильного VPN-клиента БобрДобр VPN для Android.
Рабочая директория: `c:\Users\Egor\Documents\DevTools_Super_Debug\VPN-CLient-Mobile`.

Вся предварительная аналитическая работа, архитектура, ассеты и среда сборки уже подготовлены и зафиксированы в следующих документах:
1. `PROJECT_MASTER_SPEC.md` — консолидированная спецификация протоколов (AmneziaWG, OpenConnect CSTP/DTLS + uTLS), модели VpnService, защиты сокетов и разделения обязанностей Rust vs Go.
2. `DEVELOPMENT_PLAN_AND_SUBAGENTS.md` — пошаговый план разработки с закреплением выделенных субагентов за каждой фазой и спецификацией фирменного неонового UI в стиле БобрДобр VPN (Jetpack Compose).
3. `build-container/Containerfile` и `podman-build.ps1` — изолированная среда сборки на базе Podman (Android SDK 34, NDK r26c, Rust cargo-ndk, Go 1.24).
4. `assets/` — скопированные бренд-ассеты (beaver.svg, amnezia.svg, openconnect.svg, subscription.svg).

Приступаем к Фазе 1 («Инфраструктура сборки в Podman») и Фазе 2 («Нативная Android-служба VpnService и JNI-мост»). Запусти соответствующих субагентов (@agent-podman-infra и @agent-android-core) и начни формирование проекта.
```
