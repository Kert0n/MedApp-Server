# MedApp Server

REST API для синхронизации общих аптечек, препаратов и планов лечения.

## Privacy by design

Сервер хранит текущее состояние, технических пользователей и связи пользователей с аптечками, но сознательно не хранит владельца, роли, автора изменения и историю действий. Участники общей аптечки равноправны.

Share token хранится только в виде временного SHA-256 hash в локальном Aedile/Caffeine cache. Registration throttle хранит только временный HMAC от IP с process-local случайным ключом. После перезапуска эти данные исчезают.

Подробные гарантии и ограничения: [PRIVACY_MODEL.md](PRIVACY_MODEL.md).

## Технологии

- Kotlin 2 и Java 21
- Spring Boot 4, Spring MVC и Spring Security
- Spring Data JPA, PostgreSQL 18 и Flyway
- Aedile как Kotlin API над Caffeine
- Caddy, Docker и Docker Compose

## API

Основные публичные endpoints:

- `POST /auth/register` — создать технического пользователя, передав `X-Registration-Token`;
- `POST /auth/token` — получить JWT через HTTP Basic;
- `/med-kit` — управление общими аптечками;
- `/drug` — управление препаратами;
- `/using` — планы лечения и приём препаратов;
- `GET /user` — полный snapshot доступных пользователю данных.

`open-api.yaml` является проверяемым контрактом и автоматически сравнивается со Springdoc во время тестов.

## Локальная разработка

Требования: JDK 21 и Docker.

```bash
docker compose -f compose.dev.yaml up -d
./src/main/resources/certs/gen.sh
./gradlew bootRun --args='--spring.profiles.active=dev'
```

Dev profile использует локальные тестовые значения и не предназначен для публичного развёртывания. RSA keys создаются в ignored-каталоге `.local/secrets`; Gradle явно исключает любые `certs/*.pem` из main resources, поэтому локальный ключ не может случайно попасть в JAR.

Swagger UI: `http://localhost:8080/swagger-ui/index.html`.

## Тесты

```bash
./gradlew test
```

Обновление OpenAPI после намеренного изменения API:

```bash
./gradlew test --tests '*OpenApiContractTest' -DupdateOpenApi=true
./gradlew test
```

## Production

Production использует Caddy как единственную публичную точку входа. Порты application и PostgreSQL наружу не публикуются. Перед запуском требуются:

- `POSTGRES_PASSWORD`;
- `REGISTRATION_SECRET`;
- `JWT_PRIVATE_KEY_FILE`;
- `JWT_PUBLIC_KEY_FILE`.

```bash
docker compose config
docker compose up -d --build
```

Настройки и rollback описаны в [DEPLOYMENT.md](DEPLOYMENT.md), миграции — в [DATABASE_MIGRATION.md](DATABASE_MIGRATION.md).

## Registration throttle

Throttle предназначен для отсечения случайных автоматических регистраций, а не для противодействия целевой распределённой атаке.

- учитываются только успешные регистрации;
- check и reservation атомарны;
- IP не сохраняется в исходном виде;
- окно и лимит задаются через `registration.throttle.window` и `registration.throttle.max-successful-registrations`;
- состояние локально одному экземпляру приложения;
- исчерпание лимита возвращает `429`.

При горизонтальном масштабировании потребуется отдельное архитектурное решение. Оно намеренно не добавлено в текущую одноинстансную privacy-модель.

## Документация

- [ARCHITECTURE.md](ARCHITECTURE.md)
- [PRIVACY_MODEL.md](PRIVACY_MODEL.md)
- [SECURITY.md](SECURITY.md)
- [DEPLOYMENT.md](DEPLOYMENT.md)
- [DATABASE_MIGRATION.md](DATABASE_MIGRATION.md)
- [API_MIGRATION_V1.md](API_MIGRATION_V1.md)
- [CHANGELOG.md](CHANGELOG.md)
