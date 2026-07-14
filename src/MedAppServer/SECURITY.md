# Security

## Authentication

- пользователь получает UUID login и случайный authentication key;
- в БД хранится только adaptive password hash;
- `POST /auth/token` принимает HTTP Basic только через HTTPS;
- JWT подписывается RSA key, содержит `sub`, `iss`, `aud`, `iat`, `exp`;
- decoder проверяет issuer, audience и срок действия;
- token response содержит `Cache-Control: no-store`.

## Registration

`X-Registration-Token` — общий nuisance-bot secret, а не полноценная пользовательская аутентификация. Сравнение выполняется constant-time. После него применяется локальный атомарный limiter успешных регистраций.

Failed login limiter намеренно не добавлен. При необходимости его следует реализовывать на edge после отдельного анализа DoS и privacy trade-offs.

## Network trust

- Caddy перезаписывает forwarded headers;
- Tomcat доверяет только edge network CIDR;
- прямой доступ к application port закрыт;
- PostgreSQL доступен только application в internal network;
- если появляется CDN, его адреса должны быть явно добавлены в Caddy trusted proxies.

## API

- stateless API не использует cookie sessions, поэтому CSRF отключён;
- CORS по умолчанию не разрешён;
- validation применяется к request DTO;
- errors используют RFC 9457 и не возвращают внутренние exception messages;
- Actuator публикует только health без details;
- Caddy ограничивает request body 64 KB.

## Dependency security

CI запускает tests, CodeQL, dependency review, Gitleaks и Trivy. High/critical finding нельзя игнорировать без объяснения в PR.

Trivy отдельно анализирует executable JAR со всеми nested dependencies и все
три production images: application, Caddy и PostgreSQL. Caddy и `gosu`
пересобираются Go 1.26.5; OS packages обновляются во время image build.
Все три runtime containers запускаются без root; Compose дополнительно удаляет
capabilities и включает `no-new-privileges`.

Test profile использует ephemeral RSA key pair, которую Gradle генерирует в
`build/generated-test-resources` перед тестами; private key не хранится в Git.
Четыре прежних срабатывания Gitleaks для удалённой test fixture и исторической
JWT-shaped test string остаются в неизменяемой истории и перечислены точными
fingerprints в корневом `.gitleaksignore`. Широкие исключения путей или правил
намеренно не используются. Production signing keys монтируются при запуске и
не должны попадать в Git.

Dev keys создаются в ignored-каталоге `.local/secrets`. Main resource set
исключает `certs/*.pem`, поэтому даже оставшийся локальный PEM внутри source
tree не включается в JAR. Docker context исключает эти файлы независимо.

## Reporting

Не публикуйте vulnerability и secrets в public issue. Передайте maintainer минимальный reproduction, affected commit и ожидаемое влияние через приватный канал репозитория.
