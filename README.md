# Vela — VPN-клиент для Android (Kotlin + Jetpack Compose)

Сборка: откройте папку в **Android Studio (Ladybug+)** → Sync → Run на Pixel 7 (или Build ▸ Build APK).
Release-сборка: minify + shrinkResources, только arm64-v8a.

Что реализовано: интерфейс (главная, серверы, Zapret, настройки), темы Vela / Аврора / Закат / Океан
со светлым/тёмным режимом, круглые флаги, график скорости, VpnService с раздельным туннелированием,
парсер ссылок vless/trojan/ss/hy2, виброотклик.

Подписки (как в Happ, по их документации app-management): ссылка https://…, `happ://add/https://…`, `vela://add/https://…`,
вставка из буфера, QR-сканер. Параметры читаются из заголовков ответа и из строк `#параметр: значение` в теле:
profile-title (в т.ч. `base64:`), profile-update-interval (автообновление), subscription-userinfo (трафик, срок),
support-url, profile-web-page-url, announce, fallback-url, change-user-agent, subscription-request-timeout,
per-app-proxy-mode + per-app-proxy-list / -list-set (Android). Тело: список ссылок, base64-список или JSON-конфиги Xray;
vless/vmess/trojan/ss/hy2/socks/wireguard, подпись сервера `#имя?serverDescription=<base64>`. Приложение отправляет
заголовки x-hwid (случайный ID установки), x-device-os, x-ver-os, x-device-model для панелей с лимитом устройств.
Не поддерживаются: зашифрованные `happ://crypt…`, `new-url`/`new-domain` (нужен Provider ID), `per-app-proxy-list-invert`.

Сборка на GitHub: положите содержимое папки Vela в корень репозитория, `.github/workflows/main.yml` соберёт debug-APK
(Actions → Artifacts → Vela-debug-apk).

JSON-подписки (формат Happ): каждый конфиг Xray сохраняется целиком, из него берутся имя (remarks), протокол, адрес и порт.
Подключение идёт через ядро Xray: workflow скачивает libv2ray.aar (2dust/AndroidLibXrayLite) в app/libs, код вызывает его через reflection
(без AAR проект собирается и работает в демо-режиме). Нужна версия библиотеки с TUN (startLoop с дескриптором). При подключении
в конфиг подставляется TUN-вход, остальное (outbounds, routing, dns) остаётся как в подписке; geoip.dat/geosite.dat скачиваются при первом подключении.
Серверы из обычных ссылок (vless:// и т.п.) пока показываются в списке и пингуются, но через ядро не запускаются — для них нужен генератор конфигов.

Что нужно добавить для рабочего VPN: ядро (Xray / sing-box) в `TunnelCore` (Vpn.kt). Пока `isReal = false`,
сервис поднимает только служебную подсеть, поэтому интернет телефона не пропадает, а скорость в UI — демо.
Zapret: `ZapretCfg.nfqwsArgs()` формирует параметры nfqws (bol-van/zapret) для root/OpenWrt/ПК;
на Android без root для обхода DPI нужен локальный прокси внутри VpnService.
Экран «Приложения» (выбор приложений для раздельного туннелирования) в проект пока не включён,
но сервис уже читает `VpnState.apps` / `VpnState.bypass`.

Журнал: Logger.kt (VLog) — logcat (тег Vela/<модуль>), файл filesDir/logs/vela.log, последние записи в Настройках с кнопками «Поделиться» и «Очистить». Токены, UUID и ссылки серверов скрываются автоматически.
