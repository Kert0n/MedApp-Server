# Production deployment

## Required inputs

- `POSTGRES_PASSWORD`;
- `REGISTRATION_SECRET`;
- `JWT_PRIVATE_KEY_FILE`;
- `JWT_PUBLIC_KEY_FILE`;
- DNS record для домена из `Caddyfile`.

RSA files не должны находиться в `src/main/resources`: `.dockerignore` исключает PEM и настоящий `application-prod.properties` из build context.

## Preflight

```bash
export POSTGRES_PASSWORD='...'
export REGISTRATION_SECRET='...'
export JWT_PRIVATE_KEY_FILE='/secure/path/jwt-private.pem'
export JWT_PUBLIC_KEY_FILE='/secure/path/jwt-public.pem'

docker compose config
docker compose build
```

Перед первым запуском на существующей БД выполните процедуру из [DATABASE_MIGRATION.md](DATABASE_MIGRATION.md).

## Start

```bash
docker compose up -d
curl --fail https://medapp.el-communication.ru/actuator/health
```

В production наружу публикуются только Caddy `80/443`. App `8080` и PostgreSQL `5432` доступны только внутри Docker networks.

Production Compose собирает hardened Caddy и PostgreSQL images локально.
Alpine packages обновляются при сборке, а Caddy и используемый PostgreSQL
entrypoint-ом `gosu` пересобираются на зафиксированном Go toolchain с
актуальными stdlib security fixes. Поэтому production images необходимо
регулярно пересобирать, даже если application code не менялся.

## Logs

Production Compose использует `logging.driver: none`. `docker compose logs` не является способом диагностики production. Проверка состояния выполняется health endpoint и состоянием контейнеров.

## Rollback

1. Остановить запись трафика.
2. Остановить stack.
3. Восстановить pre-migration database backup, если была применена необратимая migration.
4. Запустить предыдущий image и совместимую конфигурацию.
5. Проверить health до возврата трафика.

Не откатывайте только application image после применения несовместимой schema migration.
