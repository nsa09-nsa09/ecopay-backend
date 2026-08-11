# EcoPay Production Checklist

All items stay unchecked until verified against the real production environment or an automated release gate.

- [ ] DB backup exists and restore has been tested against a disposable database.
- [ ] Flyway clean upgrade test passes from an empty database using the production artifact.
- [ ] Existing database migration history reviewed: no applied duplicate `V61__stories` conflict remains before deploy.
- [ ] FreedomPay LIVE merchant id, payment secret, and payout secret are configured.
- [ ] FreedomPay callbacks are public HTTPS URLs: result, payout-result, success, and failure.
- [ ] FreedomPay webhook delivery test completed against the public production callback.
- [ ] Refund test completed with the live provider flow.
- [ ] Payout card binding and payout dispatch test completed with the live provider flow.
- [ ] Real SMS provider is configured with `SMS_PROVIDER=mobizon`; `APP_PHONE_DEV_BYPASS_CODE` is empty.
- [ ] Real SMTP is configured and startup SMTP check is enabled.
- [ ] CORS uses exact HTTPS origins only.
- [ ] JWT secret and field-encryption key are strong base64 secrets with at least 32 decoded bytes.
- [ ] S3/R2 bucket, endpoint, access key, and secret key are configured and upload/read verified.
- [ ] `/actuator/health` responds through the production ingress without exposing details.
- [ ] Production logs were sampled and contain no JWTs, encryption keys, payment secrets, SMS API keys, or verification codes.
- [ ] Frontend release gate passes: `npm run release:gate`.
- [ ] Backend release gate passes: duplicate Flyway check plus `./mvnw -B verify`.
- [ ] Docker Compose config validates with the real Dokploy environment.
- [ ] Rollback procedure is documented with the previous image tag, database backup, and operator owner.
