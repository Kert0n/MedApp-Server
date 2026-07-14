# ADR 0002: Local registration throttle

Status: accepted.

## Decision

Использовать локальный Aedile/Caffeine cache с атомарным счётчиком и process-local HMAC key. Считать только успешные регистрации.

## Consequences

- защита соответствует цели отсечения случайных ботов;
- IP не хранится в исходном виде;
- restart сбрасывает состояние;
- каждый application replica имеет независимый лимит;
- distributed storage добавляется только при реальном горизонтальном масштабировании.
