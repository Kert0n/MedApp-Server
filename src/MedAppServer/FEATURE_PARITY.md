# Feature parity

Финальная DDD-цепочка сохраняет полезное поведение из архивных PR, но не их
промежуточную архитектуру.

| Область | Итоговый контракт | Gate |
|---|---|---|
| Quantity | `BigDecimal`, `NUMERIC(19,6)`, rounding down | `DrugAggregateTest`, `QuantityScaleTest` |
| Drug/plan | consumption, intake, reconciliation, move, cascade | domain/application/repository tests |
| MedKit | equal members, join/leave/delete, bulk transfer | `MedKitApplicationTest` |
| Intake | cache hit/conflict/retry, cache after commit, concurrent ID | application/query-plan/concurrency tests |
| REST | resource v1 routes, no aliases, DTO descriptions | `ResourceApiContractTest`, OpenAPI snapshot |
| Auth | malformed Basic, 429 throttling, secret guard, JWT | auth/security integration tests |
| Catalogue | direct data-only load, multilingual indexed search | fuzzy-search and query-plan tests |
| Schema | clean create, cascades, indexes, Hibernate validation | `SchemaContractTest`, `queryPlanTest` startup |
| SQL | N+1 scaling, fingerprints, parameters and EXPLAIN | generated query-plan reports |
| Concurrency | serialize Drug writes; ordered move/delete locks | `ConcurrentCommandsTest`, `PessimisticLockTest` |

Не переносятся: legacy REST aliases, `@Formula` в write model, обратные JPA-коллекции,
repository-доступ из orchestrator и ALTER/migration-скрипты.
