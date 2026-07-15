# Changelog

## Unreleased

### API

- token issuance перенесён на `POST /auth/token`;
- registration limit возвращает `429`;
- quantities сериализуются как decimal numbers.

### Data

- schema управляется Flyway;
- quantities хранятся как `NUMERIC(19,6)`;
- concurrent stock changes сериализуются без version field.

### Development and operations

- local dev/test JWT keys создаются один раз и переиспользуются;
- rotation выполняется только через `gen.sh --force`;
- production console logs доступны с bounded rotation;
- CI проверяет только current tree и новые commit ranges на secrets.

### Privacy invariants

- owner, roles, audit/history и author metadata не добавлены;
- участники общей аптечки остаются равноправными.
