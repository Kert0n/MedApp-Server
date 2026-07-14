# Changelog

## Unreleased

### Changed

- registration throttle стал атомарным и privacy-preserving;
- trusted proxy boundary согласован между Caddy и Spring Boot;
- registration rate-limit response изменён на `429`;
- token endpoint изменён на `POST /auth/token` с JSON response;
- JWT получил issuer и audience validation;
- quantities переведены на `BigDecimal`/`NUMERIC(19,6)`;
- stock operations используют pessimistic locking без entity versions;
- errors нормализованы через Problem Details;
- production Compose изолирует application и PostgreSQL;
- Spring Boot обновлён до `4.0.7`, включая исправленные Spring Security, Tomcat, Jackson и PostgreSQL JDBC;
- production images Caddy и PostgreSQL собираются из обновлённых Alpine-баз с пересобранными Go binaries;
- schema управляется Flyway;
- OpenAPI проверяется против generated contract;
- CI проверяет историю на secrets, dependency changes, исходники, executable JAR и все production images;
- CI запускает production smoke-test с чистой PostgreSQL и временными RSA-ключами.

### Privacy

- Caddy access logs отключены;
- Docker log persistence отключён в production;
- raw IP больше не хранится в registration cache;
- JPA `User` отделён от security principal;
- owner, roles, audit history и version columns не добавлены.
