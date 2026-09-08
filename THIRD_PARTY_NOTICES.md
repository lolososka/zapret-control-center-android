# Сторонние компоненты и авторские права

Zapret Mobile является форком открытого проекта [ByeDPIAndroid](https://github.com/dovecoteescapee/ByeDPIAndroid). Этот файл не заменяет тексты лицензий. Он помогает связать компоненты, авторов и оригинальные условия распространения.

## Основной проект

- **ByeDPIAndroid** — автор и сопровождающий исходного проекта: [dovecoteescapee](https://github.com/dovecoteescapee).
- Исходная история и авторство коммитов сохранены в Git.
- Код приложения и изменения форка распространяются по GNU General Public License version 3; полный текст находится в [`LICENSE`](LICENSE).
- При распространении изменённого APK необходимо предоставить соответствующий исходный код этой версии на условиях GPL v3 и сохранить уведомления об авторских правах.

## Встроенные нативные компоненты

| Компонент | Зафиксированная ревизия | Лицензия | Где находится текст |
| --- | --- | --- | --- |
| [ByeDPI](https://github.com/hufrea/byedpi) | `v0.13` / `078842b084853bc30f33eaaec7acc510cf67e560` | MIT | [`app/src/main/cpp/byedpi/LICENSE`](app/src/main/cpp/byedpi/LICENSE) |
| [hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel) | `2.7.4` / `677bb4530cfc867cd44d88d298960c8d8d9fbfad` | MIT | [`app/src/main/jni/hev-socks5-tunnel/License`](app/src/main/jni/hev-socks5-tunnel/License) |
| hev-socks5-tunnel core | `103d99705afcc7663a7e7dcee11a94850da263e0` | MIT | [`app/src/main/jni/hev-socks5-tunnel/src/core/License`](app/src/main/jni/hev-socks5-tunnel/src/core/License) |
| [hev-task-system](https://github.com/heiher/hev-task-system) | `5.3.2` / `b2be0b33a236b4776ff92425f61bfa12b2a2a6fd` | MIT | [`app/src/main/jni/hev-socks5-tunnel/third-part/hev-task-system/License`](app/src/main/jni/hev-socks5-tunnel/third-part/hev-task-system/License) |
| [lwIP](https://savannah.nongnu.org/projects/lwip/) | `9f5fa882d5a2675aae9907be21b463359ba8b632` | BSD 3-Clause | [`app/src/main/jni/hev-socks5-tunnel/third-part/lwip/License`](app/src/main/jni/hev-socks5-tunnel/third-part/lwip/License) |
| libyaml (в составе туннеля) | `162227cd7d2b6108bc8bc133273e11413222ddf4` | MIT | [`app/src/main/jni/hev-socks5-tunnel/third-part/yaml/License`](app/src/main/jni/hev-socks5-tunnel/third-part/yaml/License) |
| KAVL header by Attractive Chaos | включён в ByeDPI | MIT | [`app/src/main/cpp/byedpi/kavl.h`](app/src/main/cpp/byedpi/kavl.h) |

Точные ревизии верхнеуровневых подмодулей закреплены в Git-дереве репозитория. Вложенные ревизии закреплены деревом `hev-socks5-tunnel`.

## Android-библиотеки

Сборка также использует следующие библиотеки из Maven-репозиториев:

- AndroidX Core, Fragment, AppCompat, Preference и Lifecycle — Apache License 2.0;
- Material Components for Android — Apache License 2.0;
- PreferenceX — Apache License 2.0 согласно опубликованным Maven-метаданным;
- JUnit 4 — Eclipse Public License 1.0;
- AndroidX Test и Espresso — Apache License 2.0.

Их точные версии объявлены в [`app/build.gradle.kts`](app/build.gradle.kts). Зависимости остаются собственностью соответствующих авторов и распространяются на условиях своих лицензий.

## Обновление этого файла

При обновлении подмодуля или добавлении зависимости необходимо до выпуска APK:

1. проверить файл лицензии и авторские уведомления новой ревизии;
2. обновить таблицу и зафиксированный идентификатор версии;
3. убедиться, что обязательные тексты лицензий доступны вместе с исходниками и выпуском;
4. не удалять уведомления исходных авторов.
