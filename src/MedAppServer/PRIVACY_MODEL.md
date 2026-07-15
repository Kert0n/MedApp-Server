# Privacy model

## Что сервер знает

Сервер неизбежно знает:

- существование технического user ID;
- hash authentication key;
- membership пользователя в аптечках;
- текущее содержимое аптечки;
- текущие treatment plans пользователя;
- данные текущего HTTP-запроса во время его обработки.

## Что сервер намеренно не хранит

- персональные данные и контакты;
- owner аптечки и роли;
- автора изменения;
- причины изменения;
- историю действий и audit trail;
- login history;
- исходные share tokens;
- исходные IP в application cache;
- persistent registration throttle;
- entity version columns.

Отсутствие author metadata означает, что после изменения можно увидеть новое состояние, но нельзя определить, какой участник его создал.

## Logs

Production Caddy access log отключён. Docker logging driver для application, Caddy и PostgreSQL установлен в `none`, поэтому stdout/stderr контейнеров не сохраняются Docker daemon.

Application не пишет пользовательские identifiers и medication values на INFO/WARN. Dev logs не являются частью production privacy guarantee.

Host, cloud provider, kernel, DNS и внешняя сеть остаются вне контроля application. Оператор должен отдельно проверить, что reverse proxy перед Caddy, hosting provider и network equipment не создают собственные access logs.

## Tokens

Authentication key возвращается один раз при регистрации. Сервер хранит только password hash и не может восстановить исходный key. Account recovery намеренно отсутствует.

Share token хранится в cache только как SHA-256 hash и исчезает после TTL или restart. Token многоразовый в пределах TTL.

## Registration IP

IP используется только как вход HMAC-SHA-256. HMAC key генерируется на startup и не сохраняется. Поэтому cache key нельзя стабильно сопоставить между рестартами и нельзя проверить перебором без process-local key.

## Принятый trade-off

Модель уменьшает объём данных, полезных при компрометации, но не делает сервер «невзламываемым». Компрометация работающего процесса всё ещё даёт доступ к текущим запросам, расшифрованному состоянию в памяти, базе данных и signing key.
