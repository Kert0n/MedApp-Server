# ADR 0003: Pessimistic locking without versions

Status: accepted.

## Decision

Сериализовать конкурентные изменения stock через database pessimistic lock на существующей строке drug. Не добавлять `@Version`.

## Consequences

- схема не получает дополнительную metadata;
- отрицательные остатки предотвращаются внутри транзакции;
- конкурирующие операции ждут освобождения lock;
- транзакции должны оставаться короткими и брать locks в детерминированном порядке.
