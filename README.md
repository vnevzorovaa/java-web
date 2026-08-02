# devops-test-task

Web-приложение, которое отдаёт HTML-страницу с картинкой `main.jpg`.

## Технологии

- Java 21
- Spring Boot 4.0.7 — Spring Web MVC (встроенный Tomcat), Spring Boot Actuator
- Maven (в репозитории есть Maven Wrapper, отдельная установка Maven не нужна)

## Сборка и запуск

```bash
./mvnw clean package
java -jar target/devops-test-task-1.0.0.jar
```

Или без сборки jar:

```bash
./mvnw spring-boot:run
```

В Windows вместо `./mvnw` используйте `mvnw.cmd`. При первом запуске wrapper скачивает Maven 3.9.11, поэтому нужен доступ в интернет.

## API

| Метод | URL | Описание |
|-------|-----|----------|
| `GET` | `http://localhost:8080/` | HTML-страница с картинкой `main.jpg` |

Сама картинка доступна по `http://localhost:8080/main.jpg`.

## Healthcheck (Actuator)

| URL | Описание |
|-----|----------|
| `http://localhost:8080/actuator/health` | Общий статус приложения |
| `http://localhost:8080/actuator/health/liveness` | Liveness probe |
| `http://localhost:8080/actuator/health/readiness` | Readiness probe |

## Конфигурация

Конфигурируемый параметр — порт приложения, по умолчанию `8080` (`src/main/resources/application.yml`):

```yaml
server:
  port: ${SERVER_PORT:8080}
```

Переопределение без пересборки:

```bash
SERVER_PORT=9090 java -jar target/devops-test-task-1.0.0.jar
java -jar target/devops-test-task-1.0.0.jar --server.port=9090
```

В PowerShell: `$env:SERVER_PORT="9090"; java -jar target/devops-test-task-1.0.0.jar`
