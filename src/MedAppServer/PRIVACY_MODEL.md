# Privacy model

## Что сохраняется

- случайный user identifier и hash authentication key;
- membership пользователя в общих аптечках;
- текущее содержимое аптечек и treatment plans.

## Что не сохраняется

- owner и роли участников;
- автор или причина изменения;
- audit/history действий;
- исходные authentication и share keys;
- история IP-адресов.

Все участники общей аптечки равноправны. Удаление owner/roles/audit является частью product model, а не отсутствующей функцией.

## Logs

Caddy HTTP access log отключён. Operational console logs контейнеров доступны для диагностики и ограничены Docker-ротацией. Application не пишет credentials, JWT, signing keys или datasource password.

Infrastructure вне application — hosting provider, DNS, kernel и внешняя сеть — может иметь собственную telemetry и требует отдельной настройки оператора.

## Ограничения

Privacy-by-design уменьшает объём сохранённых данных, но не делает работающий процесс или базу недоступными при их компрометации. Account recovery без authentication key намеренно отсутствует.
