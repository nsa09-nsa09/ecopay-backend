# Быстрый старт

## 1. Запуск PostgreSQL
```bash
docker-compose up -d
```

## 2. Настройка переменных окружения (опционально)
```bash
set JWT_SECRET=YourSecretKeyHere
set MAIL_USERNAME=your-email@gmail.com
set MAIL_PASSWORD=your-app-password
```

## 3. Запуск приложения
```bash
mvnw.cmd spring-boot:run
```

## 4. Тестирование API

### Регистрация
```bash
curl -X POST http://localhost:8080/api/auth/register ^
  -H "Content-Type: application/json" ^
  -d "{\"email\":\"test@example.com\",\"password\":\"password123\",\"displayName\":\"Test User\"}"
```

### Вход
```bash
curl -X POST http://localhost:8080/api/auth/login ^
  -H "Content-Type: application/json" ^
  -d "{\"email\":\"test@example.com\",\"password\":\"password123\"}"
```

### Получение профиля
```bash
curl -X GET http://localhost:8080/api/user/me ^
  -H "Authorization: Bearer YOUR_ACCESS_TOKEN"
```

## Порты
- Приложение: http://localhost:8080
- PostgreSQL: localhost:5432

## База данных по умолчанию
- Database: splitup_auth
- Username: postgres
- Password: postgres

## Готово! 🎉
API доступен на http://localhost:8080
