# Выплаты и возвраты EcoPay — запуск в прод (FreedomPay)

Короткая шпаргалка по потоку B: выплаты владельцам (reg2reg) и возвраты участникам
(revoke/cancel). Описывает схемы, переменные окружения и действия админа.

## Схема выплаты

```
hold (releaseAt = capturedAt + app.payout.hold-days)
  → due (releaseAt прошёл)
  → проверка баланса мерчанта (/api/balance_status, кэш 60с)
        баланса не хватает → ON_HOLD (MERCHANT_BALANCE_LOW), повтор через 30 мин
  → комиссия (ABSORB: EcoPay платит; DEDUCT: вычитается у владельца)
  → reg2reg (/api/reg2reg): pg_card_token_to, pg_order_id, pg_amount,
        pg_user_id (u<publicId>), pg_order_time_limit (Алматы, +24ч), pg_description
        ответ ok → SUCCESS;  process → PENDING_PROVIDER;  error → классификация
  → callback /payout-result или опрос getPayoutStatus → SUCCESS / REQUIRES_REVIEW
```

Повторная отправка после сетевой ошибки сначала делает `getPayoutStatus` по старому
`pg_order_id`: если провайдер уже знает выплату — она НЕ отправляется второй раз
(переходит в `PENDING_PROVIDER`). Так гарантируется «ни одной двойной выплаты».

Классификация ошибок reg2reg: недействительная/просроченная карта → `PENDING_METHOD`
(владельцу: «подключите новую карту»); временная ошибка → ретрай; прочее → `REQUIRES_REVIEW`.

Минимальная выплата (`app.payout.min-amount-kzt`): суммы ниже минимума копятся в батче
владельца, но не дольше `app.payout.max-defer-days`.

## Схема возврата

```
создан (PENDING; при выключенном флаге — PENDING_MANUAL)
  → выбор вызова: клиринг пройден → /revoke (pg_refund_amount, частичный);
        авторизован, но не списан → /cancel (только полная сумма)
  → ответ pending → PENDING_PROVIDER
  → подтверждение только через get_status3: провайдерская сумма возвратов
        выросла на сумму этого возврата (база — provider_refunded_before) → SUCCESS
  → не видно дольше 24ч → REQUIRES_REVIEW
```

Несколько частичных возвратов по одному платежу суммарно ≤ captured; подтверждаются по
снимку `provider_refunded_before`, снятому в момент отправки. Фискальный чек возврата
(`pg_receipt_positions[]`) добавляется при `ecopay.payments.fiscal.enabled=true`; если сумма
позиций ≠ сумме возврата — возврат не отправляется (`REQUIRES_REVIEW`, `FISCAL_MISMATCH`).

## Переменные окружения

| Переменная | Прод | Назначение |
|---|---|---|
| `APP_MONEY_LIVE_ENABLED` | `true` | мастер-флаг движения денег |
| `APP_MONEY_PAYOUT_DISPATCH_ENABLED` | `true` | включить отправку выплат |
| `APP_MONEY_REFUND_DISPATCH_ENABLED` | `true` | включить отправку возвратов |
| `APP_PAYOUT_HOLD_DAYS` | `30` | резерв доли владельца |
| `APP_PAYOUT_BALANCE_RESERVE_KZT` | `0` | неснижаемый остаток баланса |
| `APP_PAYOUT_PROVIDER_FEE_MODE` | `ABSORB` | `ABSORB` \| `DEDUCT` |
| `APP_PAYOUT_PROVIDER_FEE_FIXED_KZT` | уточнить | фикс. комиссия reg2reg |
| `APP_PAYOUT_PROVIDER_FEE_PERCENT` | уточнить | процентная комиссия reg2reg |
| `APP_PAYOUT_MIN_AMOUNT_KZT` | `0` | минимальная выплата |
| `APP_PAYOUT_MAX_DEFER_DAYS` | `7` | макс. накопление ниже минимума |
| `APP_REFUND_MAX_ATTEMPTS` | `8` | попытки отправки возврата |
| `APP_REFUND_EXPECTED_DAYS` | `7` | срок зачисления для участника (текст) |
| `FISCAL_ENABLED` | уточнить | фискальные чеки (OFD) |
| `FREEDOMPAY_PAYOUT_SECRET_KEY` | секрет | подпись reg2reg/баланса/статуса выплат |

`ecopay.payments.freedompay.balance-status-uses-payout-secret` по умолчанию `true`
(каким секретом подписывать `balance_status` — уточнить у FreedomPay, см. ниже).

## Что делать админу

- **`ON_HOLD` (MERCHANT_BALANCE_LOW)** — баланса выплат не хватает. Пополнить баланс
  мерчанта; выплаты уйдут автоматически на следующем тике. Очередь видна в
  `GET /admin/finance/config` (`payoutsWaitingForBalance`).
- **`PENDING_MANUAL` (возврат)** — автоотправка выключена. Вернуть деньги вручную в
  кабинете FreedomPay, затем `POST /admin/finance/refunds/{id}/mark-sent-manually`
  `{providerRef, note}` → статус `SUCCESS_MANUAL`.
- **`REQUIRES_REVIEW`** — операция застряла/неоднозначна. Проверить в
  `GET /admin/finance/stuck?type=PAYOUT|REFUND`, разобраться, затем
  `POST /admin/finance/payouts/{id}/retry` или `/admin/finance/refunds/{id}/retry`
  (повтор идемпотентен — сначала сверка статуса у провайдера).

## Вопросы менеджеру FreedomPay

1. Каким секретом подписывать `/api/balance_status` — merchant или payout?
2. Комиссия reg2reg: фиксированная и/или процентная? Какой fee-mode выбрать?
3. Лимиты на одну выплату и в сутки.
4. Срок, в который можно сделать возврат после платежа.
5. Поддерживается ли фискализация возвратов (`pg_receipt_positions[]` в `/revoke`)?
