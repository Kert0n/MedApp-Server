# Security policy

Production credentials, registration secret и JWT signing keys передаются приложению при запуске и не должны храниться в Git или application image.

Локальная RSA-пара находится в ignored-каталоге `.local/secrets`. Она создаётся один раз и меняется только явной ротацией. CI использует отдельную временную пару.

CI проверяет новые изменения, зависимости, исходники и production artifacts. Найденные secrets и vulnerabilities нельзя публиковать в public issue или PR comment. Передавайте maintainer только минимальный reproduction и affected version через приватный канал репозитория.
