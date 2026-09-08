# Zapret Mobile

[![Android CI](https://github.com/lolososka/zapret-control-center-android/actions/workflows/android-ci.yml/badge.svg)](https://github.com/lolososka/zapret-control-center-android/actions/workflows/android-ci.yml)
[![Лицензия: GPL v3](https://img.shields.io/badge/license-GPLv3-151515.svg)](LICENSE)

Android-приложение от **lolososka** в стиле Zapret Control Center. Оно запускает локальный VPN-интерфейс Android и направляет трафик через локальный SOCKS5-прокси [ByeDPI](https://github.com/hufrea/byedpi). Root-права не нужны.

> 0.1.3 — круглая кнопка запуска, режим VPN/прокси, Telegram-совместимость и тёмная панель. Подписанный APK публикуется в Releases после автоматических проверок. Работа обхода на конкретном телефоне и у конкретного провайдера требует проверки.

## Что важно знать

- поддерживается Android 5.0 и новее (`minSdk 21`);
- обработка происходит на устройстве, собственного удалённого VPN-сервера у проекта нет;
- приложение не меняет внешний IP-адрес и не добавляет шифрование поверх HTTPS;
- Android показывает системный значок VPN, потому что для перенаправления трафика используется `VpnService`;
- одновременно обычно может работать только одно VPN-приложение, поэтому возможен конфликт с AdGuard, другими VPN и сетевыми фильтрами;
- подходящая стратегия зависит от сети и провайдера.

## Установка

Подписанные APK публикуются в [GitHub Releases](https://github.com/lolososka/zapret-control-center-android/releases) и на [сайте Zapret Control Center](https://lolososka.github.io/zapret-discord-youtube/#android). Скачайте APK на телефон, разрешите установку из выбранного браузера, откройте приложение и подтвердите системный запрос VPN при первом подключении.

Контрольная сумма SHA-256 и архив исходного кода с подмодулями приложены к каждому выпуску. Debug APK из Actions предназначен для разработки и подписан другим ключом.

## Сборка из исходников

Текущий проект намеренно сохраняет проверенный стек исходного форка:

| Компонент | Версия |
| --- | --- |
| Android Gradle Plugin | 8.3.0 |
| Gradle Wrapper | 8.4 |
| Kotlin | 1.9.22 |
| compileSdk / targetSdk | 34 / 34 |
| Android NDK | 26.1.10909125 |
| CMake | 3.22.1 |
| Java для Gradle | 17 |

Понадобятся Android SDK 34, Android NDK 26.1.10909125 и CMake 3.22.1. Клонировать репозиторий нужно вместе с подмодулями:

```powershell
git clone --recurse-submodules https://github.com/lolososka/zapret-control-center-android.git
cd zapret-control-center-android
.\gradlew.bat lintDebug testDebugUnitTest assembleDebug
```

Debug APK появится в `app/build/outputs/apk/debug/`.

Порядок подготовки публичной версии описан в [RELEASING.md](RELEASING.md). Он отделяет проверочные debug-сборки от подписанных выпусков.

## Происхождение проекта

Это самостоятельный форк [ByeDPIAndroid](https://github.com/dovecoteescapee/ByeDPIAndroid), который развивает **dovecoteescapee**. История Git сохранена, а удалённый репозиторий `upstream` используется для аккуратной синхронизации с оригиналом.

Основные компоненты:

- [ByeDPIAndroid](https://github.com/dovecoteescapee/ByeDPIAndroid) — исходная Android-реализация;
- [ByeDPI](https://github.com/hufrea/byedpi) — локальный SOCKS5-прокси и DPI-десинхронизация;
- [hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel) — туннель между Android VPN и SOCKS5.

Название Zapret Mobile относится к этому форку. Приложение не является официальным клиентом проектов ByeDPI, ByeDPIAndroid или `bol-van/zapret` и не содержит Windows-компоненты WinDivert.

## Конфиденциальность

В приложении нет учётной записи, аналитики и телеметрии проекта. Обычный сетевой трафик по-прежнему уходит к сайтам и сервисам, которые открывает пользователь. Перед установкой сторонней сборки сравните её источник и SHA-256 с официальным выпуском.

## Лицензия и благодарности

Основной код приложения и изменения этого форка распространяются по [GNU General Public License v3](LICENSE). Авторские права исходных разработчиков сохранены. Список встроенных компонентов и их лицензий находится в [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

Ошибки и предложения принимаются в [Issues](https://github.com/lolososka/zapret-control-center-android/issues). О проблемах безопасности сообщайте по инструкции в [SECURITY.md](SECURITY.md).
