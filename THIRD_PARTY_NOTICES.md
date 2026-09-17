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
| [ByeDPI](https://github.com/lolososka/byedpi) (на основе [hufrea/byedpi](https://github.com/hufrea/byedpi)) | `v0.13 + UDP patch` / `86348a4e1965039cb5df8aa5332154f50302679e` | MIT | [`app/src/main/cpp/byedpi/LICENSE`](app/src/main/cpp/byedpi/LICENSE) |
| [hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel) | `2.17.1` / `9a06bc6e7989da54e3d32ff701ef7a7ce4995d3a` | MIT | [`app/src/main/jni/hev-socks5-tunnel/LICENSE`](app/src/main/jni/hev-socks5-tunnel/LICENSE) |
| hev-socks5-tunnel core | `1.6.4` / `162dd996299fc2d2bff2dd63728f8a2cd71ed31a` | MIT | [`app/src/main/jni/hev-socks5-tunnel/src/core/LICENSE`](app/src/main/jni/hev-socks5-tunnel/src/core/LICENSE) |
| [hev-task-system](https://github.com/heiher/hev-task-system) | `5.10.3` / `328f35d903221b51811b3d02b277d665dfbdc75f` | MIT | [`app/src/main/jni/hev-socks5-tunnel/third-part/hev-task-system/LICENSE`](app/src/main/jni/hev-socks5-tunnel/third-part/hev-task-system/LICENSE) |
| [lwIP](https://savannah.nongnu.org/projects/lwip/) | `2.2.1.7` / `2a11c14c7a32887af25a034e82ef18b0b12076ac` | BSD 3-Clause | [`app/src/main/jni/hev-socks5-tunnel/third-part/lwip/LICENSE`](app/src/main/jni/hev-socks5-tunnel/third-part/lwip/LICENSE) |
| libyaml (в составе туннеля) | `0.2.5.2` / `efa36117a8646d26d12b58e05bac472d7854a70d` | MIT | [`app/src/main/jni/hev-socks5-tunnel/third-part/yaml/LICENSE`](app/src/main/jni/hev-socks5-tunnel/third-part/yaml/LICENSE) |
| KAVL header by Attractive Chaos | включён в ByeDPI | MIT | [`app/src/main/cpp/byedpi/kavl.h`](app/src/main/cpp/byedpi/kavl.h) |
| [tg-ws-proxy](https://github.com/amurcanov/tg-ws-proxy-android) / Flowseal core | snapshot used by Zapret Mobile 0.2.0 | GPL-3.0 (core derived from MIT Flowseal code) | [`third_party/tg-ws-proxy/LICENSE`](third_party/tg-ws-proxy/LICENSE), [`third_party/tg-ws-proxy/src`](third_party/tg-ws-proxy/src) |

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
