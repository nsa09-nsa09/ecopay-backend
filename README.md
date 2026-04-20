# Ecopay Backend

## CI/CD

- `CI`: GitHub Actions workflow `.github/workflows/ci.yml` runs Maven verification on Java 21 with a PostgreSQL 15 service.
- `CD`: GitHub Actions workflow `.github/workflows/cd.yml` builds the app Docker image and publishes it to `ghcr.io/<owner>/<repo>` on pushes to `main` and on manual runs.
- `Docker`: the repository now includes a production `Dockerfile` and `.dockerignore` for image builds.

### GitHub setup

- Make sure your default branch is `main` if you want automatic image publication from the default configuration.
- In repository settings, allow GitHub Actions to read/write packages so pushes to GHCR succeed.
- Published image tags include the branch name, commit SHA, and `latest` for the default branch.

Сервис авторизации и аутентификации с поддержкой JWT токенов.

## Технологии

- Spring Boot 4.0.2
- Spring Security
- Spring Data JPA
- PostgreSQL
- JWT (jjwt 0.12.6)
- Lombok
- Java Mail

## Требования

- Java 21
- PostgreSQL 12+
- Maven 3.6+

## Настройка базы данных

Создайте базу данных PostgreSQL:

```sql
CREATE DATABASE ecopay;
```

## Конфигурация

Настройте параметры в `application.properties`:

```properties
spring.datasource.url=jdbc:postgresql://localhost:5432/ecopay
spring.datasource.username=postgres
spring.datasource.password=postgres

jwt.secret=YOUR_SECRET_KEY
jwt.access-expiration=900000
jwt.refresh-expiration=604800000

spring.mail.username=YOUR_EMAIL
spring.mail.password=YOUR_EMAIL_PASSWORD
```

## Запуск

```bash
./mvnw.cmd spring-boot:run
```

## API Endpoints

### Регистрация
**POST** `/api/auth/register`

Request:
```json
{
  "email": "user@example.com",
  "password": "password123",
  "displayName": "John Doe",
  "phoneNumber": "+77001234567"
}
```

Response:
```json
{
  "accessToken": "eyJhbGc...",
  "refreshToken": "uuid-token",
  "user": {
    "id": 1,
    "email": "user@example.com",
    "displayName": "John Doe",
    "phoneNumber": "+77001234567",
    "avatar": null,
    "status": "ACTIVE",
    "reputation": 0
  }
}
```

### Вход
**POST** `/api/auth/login`

Request:
```json
{
  "email": "user@example.com",
  "password": "password123"
}
```

Response: аналогичен регистрации

### Обновление токена
**POST** `/api/auth/refresh`

Request:
```json
{
  "refreshToken": "uuid-token"
}
```

Response: аналогичен регистрации

### Выход
**POST** `/api/auth/logout`

Request:
```json
{
  "refreshToken": "uuid-token"
}
```

### Восстановление пароля
**POST** `/api/auth/reset-password`

Request:
```json
{
  "email": "user@example.com"
}
```

### Подтверждение сброса пароля
**POST** `/api/auth/reset-password/confirm`

Request:
```json
{
  "token": "reset-token",
  "newPassword": "newpassword123"
}
```

### Получение текущего пользователя
**GET** `/api/user/me`

Headers:
```
Authorization: Bearer {accessToken}
```

Response:
```json
{
  "id": 1,
  "email": "user@example.com",
  "displayName": "John Doe",
  "phoneNumber": "+77001234567",
  "avatar": null,
  "status": "ACTIVE",
  "reputation": 0
}
```

## Безопасность

- Пароли хэшируются с использованием BCrypt
- JWT токены для аутентификации
- Access token: 15 минут
- Refresh token: 7 дней
- Rate limiting: 5 попыток входа за 15 минут
- Email уникален
- Минимальная длина пароля: 8 символов

## Статусы пользователей

- `ACTIVE` - активный пользователь
- `BANNED` - заблокированный пользователь

## Структура проекта

```
src/main/java/kz/hrms/splitupauth/
├── controller/          # REST контроллеры
├── dto/                 # Data Transfer Objects
├── entity/              # JPA Entity
├── exception/           # Обработка исключений
├── repository/          # Spring Data репозитории
├── scheduler/           # Планировщики задач
├── security/            # Конфигурация безопасности
├── service/             # Бизнес-логика
└── util/                # Утилиты (JWT)
```

## Автоматическая очистка

Система автоматически очищает:
- Истекшие refresh токены (ежедневно в 02:00)
- Старые попытки входа (ежедневно в 03:00)
