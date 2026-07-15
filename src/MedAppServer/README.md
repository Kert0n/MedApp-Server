# MedApp Server

Backend общей домашней аптечки на Kotlin и Spring Boot.

Участники аптечки равноправны. Сервер не хранит владельца, роли, автора изменения или историю действий. Authentication key возвращается при регистрации один раз; в базе остаётся только его hash.

## Требования

- JDK 21;
- Docker и Docker Compose;
- OpenSSL для локальной генерации JWT keys.

## Локальный запуск

Один раз создайте локальную RSA-пару:

```bash
./src/main/resources/certs/gen.sh
```

Повторный вызов проверяет и повторно использует существующие файлы в `.local/secrets`. Поэтому JWT остаются валидными между Gradle, IDE и debug-запусками.

Явная ротация:

```bash
./src/main/resources/certs/gen.sh --force
```

После ротации ранее выданные JWT перестают проверяться.

Запустите development database и приложение:

```bash
docker compose -f compose.dev.yaml up -d
SPRING_PROFILES_ACTIVE=dev ./gradlew bootRun
```

OpenAPI в dev доступен через `/swagger-ui/index.html` и `/v3/api-docs`.

## Проверка

```bash
./gradlew clean test bootJar --no-daemon
```

## Документация

- [ARCHITECTURE.md](ARCHITECTURE.md) — компоненты и data flow;
- [PRIVACY_MODEL.md](PRIVACY_MODEL.md) — какие данные сохраняются;
- [API_MIGRATION_V1.md](API_MIGRATION_V1.md) — изменения HTTP API;
- [DATABASE_MIGRATION.md](DATABASE_MIGRATION.md) — миграция базы;
- [DEPLOYMENT.md](DEPLOYMENT.md) — production запуск и диагностика.
