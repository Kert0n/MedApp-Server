# API migration

## Authentication

Старое:

```http
GET /auth/login
Authorization: Basic ...
```

Новое:

```http
POST /auth/token
Authorization: Basic ...
```

Ответ:

```json
{
  "accessToken": "...",
  "tokenType": "Bearer",
  "expiresIn": 600
}
```

Token response запрещено кешировать.

## Registration

- success: `200 → 201`;
- exhausted throttle: `504 → 429`;
- header `X-Registration-Token` сохраняется;
- response fields `login` и `key` сохраняются.

## Quantities

JSON type остаётся `number`, но сервер обрабатывает значения как exact decimal с максимум шестью знаками после запятой. Клиент не должен отправлять `NaN` или infinity.

## Errors

Ошибки возвращаются как `application/problem+json`. Клиент должен использовать `status` и `type`, а не сравнивать текст `detail`.
