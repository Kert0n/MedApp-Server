# Production deployment

## Перед запуском

1. Сделайте backup PostgreSQL.
2. Проверьте данные по [DATABASE_MIGRATION.md](DATABASE_MIGRATION.md).
3. Подготовьте отдельные production JWT keys вне repository.
4. Задайте required environment variables:

```bash
export POSTGRES_PASSWORD='replace-me'
export REGISTRATION_SECRET='replace-me'
export JWT_PRIVATE_KEY_FILE='/secure/path/jwt-private.pem'
export JWT_PUBLIC_KEY_FILE='/secure/path/jwt-public.pem'
```

Каталог с ключами должен быть доступен только оператору. Для standalone Compose файлы должны читаться non-root application container.

## Запуск

```bash
docker compose config
docker compose up -d --build
docker compose ps
```

Application health:

```bash
docker compose exec -T med-app-server \
  wget --quiet --output-document=- http://localhost:8080/actuator/health
```

## Diagnostics

```bash
docker compose logs --tail=200 med-app-server
docker compose logs --tail=200 postgres
docker compose logs --tail=200 caddy
```

Compose использует Docker `local` logging driver с ротацией `10 MB × 3` на service. Caddy HTTP access log не включён.

## Rollback

Application rollback выполняется возвратом предыдущего image. Если Flyway уже изменил schema, восстановите согласованный database backup; не пытайтесь автоматически откатывать destructive migration.
