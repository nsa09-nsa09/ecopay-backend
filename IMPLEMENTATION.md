# Реализованная система аутентификации и авторизации

## ✅ Реализованный функционал

### 1. Регистрация и авторизация
- ✅ Регистрация по email (обязательно)
- ✅ Телефон (опционально, с валидацией)
- ✅ Вход по email+пароль
- ✅ Восстановление пароля через email
- ✅ JWT access + refresh token

### 2. Модель пользователя
```java
User {
  id              // Long
  email           // String (unique)
  password        // String (BCrypt hash)
  displayName     // String
  phoneNumber     // String (optional)
  avatar          // String (optional)
  status          // ACTIVE/BANNED
  reputation      // Integer (default 0)
  createdAt       // LocalDateTime
  updatedAt       // LocalDateTime
}
```

### 3. Валидации
- ✅ Пароль: минимум 8 символов
- ✅ Email: уникален + валидация формата
- ✅ Лимит попыток логина: 5 попыток за 15 минут (rate limit)
- ✅ Телефон: валидация формата E.164 (опционально)

### 4. Безопасность
- ✅ BCrypt хэширование паролей
- ✅ JWT access токен (15 минут)
- ✅ JWT refresh токен (7 дней)
- ✅ Защита от brute-force атак
- ✅ Автоматическая очистка истекших токенов
- ✅ Ревокация refresh токенов
- ✅ Проверка статуса пользователя (BANNED)

## 📁 Структура проекта

### Entities (Сущности БД)
1. **User** - основная сущность пользователя
2. **RefreshToken** - refresh токены для обновления доступа
3. **PasswordResetToken** - токены для восстановления пароля
4. **LoginAttempt** - логирование попыток входа для rate limiting
5. **UserStatus** - enum (ACTIVE, BANNED)

### Repositories
- UserRepository
- RefreshTokenRepository
- PasswordResetTokenRepository
- LoginAttemptRepository

### Services
1. **AuthService** - основная бизнес-логика аутентификации
   - register()
   - login()
   - refreshToken()
   - logout()
   - requestPasswordReset()
   - confirmPasswordReset()

2. **RefreshTokenService** - управление refresh токенами
   - createRefreshToken()
   - validateRefreshToken()
   - revokeRefreshToken()
   - revokeAllUserTokens()
   - cleanupExpiredTokens()

3. **RateLimitService** - защита от brute-force
   - checkLoginAttempts()
   - recordLoginAttempt()
   - cleanupOldAttempts()

4. **EmailService** - отправка email
   - sendPasswordResetEmail()

5. **UserMapper** - маппинг User -> UserDto

### Security
1. **SecurityConfig** - конфигурация Spring Security
2. **JwtAuthenticationFilter** - фильтр для JWT токенов
3. **JwtUtil** - утилиты для работы с JWT

### Controllers
1. **AuthController** - API endpoints для аутентификации
   - POST /api/auth/register
   - POST /api/auth/login
   - POST /api/auth/refresh
   - POST /api/auth/logout
   - POST /api/auth/reset-password
   - POST /api/auth/reset-password/confirm

2. **UserController** - API для пользователя
   - GET /api/user/me

### DTOs
- RegisterRequest
- LoginRequest
- AuthResponse
- UserDto
- RefreshTokenRequest
- PasswordResetRequest
- PasswordResetConfirmRequest

### Exception Handling
- UserAlreadyExistsException
- InvalidCredentialsException
- TokenExpiredException
- TooManyLoginAttemptsException
- UserBannedException
- GlobalExceptionHandler

### Scheduler
- CleanupScheduler
  - Очистка истекших refresh токенов (ежедневно в 02:00)
  - Очистка старых попыток входа (ежедневно в 03:00)

## 🔐 Токены

### Access Token (JWT)
- Срок действия: 15 минут (900000 ms)
- Используется для авторизации запросов
- Передается в заголовке: `Authorization: Bearer {token}`

### Refresh Token (UUID)
- Срок действия: 7 дней (604800000 ms)
- Используется для получения нового access token
- Хранится в БД с возможностью ревокации

## 📊 База данных

### Таблицы
1. **users** - пользователи
2. **refresh_tokens** - refresh токены
3. **password_reset_tokens** - токены восстановления пароля
4. **login_attempts** - попытки входа

### Индексы
- users.email (unique)
- refresh_tokens.token (unique)
- refresh_tokens.user_id
- password_reset_tokens.token (unique)
- login_attempts(email, attempt_time)

## 🚀 Запуск

### Требования
- Java 21
- PostgreSQL 12+
- Maven 3.6+

### Быстрый старт с Docker
```bash
docker-compose up -d
```

### Запуск приложения
```bash
./mvnw.cmd spring-boot:run
```

### Сборка JAR
```bash
./mvnw.cmd clean package
java -jar target/split-up-auth-0.0.1-SNAPSHOT.jar
```

## 🔧 Конфигурация

### Обязательные переменные окружения
- `JWT_SECRET` - секретный ключ для JWT (base64)
- `MAIL_USERNAME` - username для SMTP
- `MAIL_PASSWORD` - пароль для SMTP

### Настройки rate limiting
- Максимум попыток: 5
- Период: 15 минут

## 📝 API Endpoints

### Публичные (без авторизации)
- POST /api/auth/register
- POST /api/auth/login
- POST /api/auth/refresh
- POST /api/auth/logout
- POST /api/auth/reset-password
- POST /api/auth/reset-password/confirm

### Защищенные (требуют JWT токен)
- GET /api/user/me

## ✨ Особенности реализации

1. **Единый аккаунт для Web и Mobile** - JWT токены универсальны
2. **Статус пользователя** - возможность блокировки (BANNED)
3. **Репутация** - поле для будущей геймификации
4. **Rate Limiting** - защита от brute-force атак
5. **Email верификация** - восстановление пароля через email
6. **Автоматическая очистка** - старые токены и логи удаляются
7. **Ревокация токенов** - logout реально отзывает refresh token
8. **Валидация данных** - на уровне DTO с Jakarta Validation
9. **Обработка ошибок** - глобальный exception handler
10. **Логирование** - все попытки входа записываются

## 🛠️ Технологии

- Spring Boot 4.0.2
- Spring Security 6.x
- Spring Data JPA
- PostgreSQL
- JWT (jjwt 0.12.6)
- Lombok
- Jakarta Validation
- Spring Mail

## 📚 Дополнительные файлы

- `README.md` - полная документация
- `api-requests.http` - примеры запросов
- `V1__init_schema.sql` - SQL скрипт инициализации БД
- `docker-compose.yml` - конфигурация для Docker
- `application-dev.properties` - профиль разработки

## ✅ Статус

Проект полностью готов к использованию. Все требования выполнены.
Успешно собран и готов к развертыванию.
