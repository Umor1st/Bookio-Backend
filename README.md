# Bookio Backend

Сервер приложения школьной библиотеки Bookio. Для работы нужны Java 21 и Docker с Compose.

Скопируйте `.env.example` в `.env` и при необходимости измените локальные параметры. Запустите PostgreSQL:

```sh
docker compose up -d
```

Перед запуском приложения загрузите переменные из `.env` в окружение текущей оболочки. Например, в PowerShell:

```powershell
Get-Content .env | Where-Object { $_ -match '^([^#=]+)=(.*)$' } | ForEach-Object { [Environment]::SetEnvironmentVariable($Matches[1], $Matches[2], 'Process') }
.\gradlew.bat run
```

Проверить запуск можно запросом `GET http://localhost:8080/health`. Порт задаётся переменной `PORT`. При запуске приложение подключается к PostgreSQL и выполняет миграции Flyway из `src/main/resources/db/migration`.

```sh
./gradlew build
./gradlew test
```

Для интеграционного теста требуется запущенный Docker. Данные PostgreSQL сохраняются в именованном volume Docker.
