# documentation

## Коротко о реализованной логике

**Билд-сервер** `139.100.237.217` 

- Jenkins
- JDK 21 
- Maven
- Git  
- Ansible  
Jenkins собирает jar, по ssh заходит на прод и развертывает его

**Production-сервер** 

- приложение как systemd-сервис (под названием web)
- nginx

Поток деплоя:

```
git push (main) → Jenkins job "devops-test-task" (Build Now)

Checkout: git clone кода на билд-сервере
Build: ./mvnw clean package 
Deploy: scp jar на прод в /opt/web/app.jar
ssh на прод → sudo systemctl restart web
Verify: /actuator/health/readiness, затем GET 
```

Приложение на проде слушает 8080 (`SERVER_ADDRESS=127.0.0.1` в systemd-юните) единственная внешняя точка входа это nginx на 80 порту. Для ssh доступа для дженкинс используется отдельная точка входа. (в jvm снаружи никак попасть нельзя)

## Важный момент и почему пришлось лезть в код 

В `src/main/java/ru/ptr/web/PageController.java` было:

```java
private static final Duration RESPONSE_DELAY = Duration.ofSeconds(1);
```

При первой сборке (даже локальной) падал тест `DevopsTestTaskApplicationTests.servesHtmlPageWithTheImage`

```
Expecting actual: 1.026166333S
to be greater than or equal to: 10S
```

То есть тест ожидает, что `GET /` отвечает за 10–20 секунд, а по факту - за 1 секунду.

Во-первых, так как это все таки тестовое задание, то очевидно, что все так просто быть не может. убедилась в том, что приложение написанно “тяжелым” намеренно. однако если бы приложение использовало обычные потоки с маленьким пулом, медленная `/` заняла бы весь пул и `/actuator/health/liveness` не смог бы получить свободный поток, чтобы ответить. однако у приложения включены виртуальные потоки (`spring.threads.virtual.enabled: true`), и они это предотвращают =\> Поэтому liveness отвечает быстро даже когда десятки клиентов одновременно висят на `/` – это и проверяет `HealthProbeUnderLoadTest` (20 параллельных медленных запросов не мешают liveness ответить меньше чем за 3 секунды)  
`1` секунда в `RESPONSE_DELAY` при этом противоречит и тесту (`DevopsTestTaskApplicationTests` ожидает ответ за 10–20 сек, а не за 1), и самой этой архитектуре – если задержка нужна именно для того, чтобы продемонстрировать, что виртуальные потоки спасают liveness под нагрузкой, она должна быть достаточно долгой, чтобы это было заметно. плюс сама константа помечена комментарием `/** Intentionally not configurable. */` . сопоставив тест, комментарий и уже заранее включенные виртуальные потоки в `application.yml` решила, что `1` это какой-то баг, который, наверное, надо было поправить. заменила `1` на `12`, все собралось 

## Инфраструктура

- Пользователь `web` - от него работает только java-процесс.
- nginx reverse proxy, `/etc/nginx/sites-available/web.conf` (сам конфиг есть в гите)
- Отдельный пользователь `deploy` – только для SSH-деплоя с билд-сервера. состоит в группе `web` (может перезаписывать `/opt/web/app.jar`), и имеет точечный passwordless sudo только на два действия:

```
deploy ALL=(root) NOPASSWD: /usr/bin/systemctl restart web
deploy ALL=(root) NOPASSWD: /usr/bin/systemctl status web
```

(файл `/etc/sudoers.d/deploy-web`, права 440, провалидирован через `visudo -cf`)

и, кстати, у этого юзера (web) нет shell. даже если взломать - на тачку не зайти

## Ручной деплой (пока без Jenkins)

```
# 1. собрать джарник
./mvnw clean package

# 2. скопировать на прод
scp target/devops-test-task-1.0.0.jar deploy@139.100.237.220:/opt/web/app.jar

# 3. порестартить сервис
ssh deploy@<IP-прод-сервера> "sudo systemctl restart web"

# 4. проверить 
ssh deploy@<IP-прод-сервера> "sudo systemctl status web"
```

## Автоматический деплой через Jenkins

Pipeline job `devops-test-task`, `Jenkinsfile` в корне репозитория, четыре стадии:

1. **Checkout** - ветка `main` в гитхаб-репозитории
2. **Build** - `chmod +x mvnw && ./mvnw clean package` (включая тесты)
3. **Deploy** - копирование джарника на прод + рестарт сервиса. все через SSH-ключ, хранящийся в Jenkins Credentials (ID `deploy-prod-key`, тип *SSH Username with private key*, username `deploy`). *ключ нигде не лежит в открытом виде*
4. **Verify** - опрашивает `/actuator/health/readiness` (до 10 попыток по 2 секунды), затем делает `GET /` и печатает код ответа 

**Запуск:** Jenkins → job `devops-test-task` → **Build Now**

SSH с билд-сервера (юзер `jenkins`) на прод (юзер `deploy`) настроен по ключу без пароля:

```
sudo -u jenkins ssh-keygen -t ed25519 -f /var/lib/jenkins/.ssh/id_ed25519 -N ""
# публичный ключ добавлен в /home/deploy/.ssh/authorized_keys на проде
```

# Excellent+ часть

### Деплой через Ansible

Реализовано как отдельный пайплайн (в целом придерживалась логики новое задание - новый конфиг):

- `Jenkinsfile.ansible` – тот же набор стадий (Checkout → Build → Deploy → Verify), но деплой вызывает `ansible-playbook` вместо `scp`/`ssh`
- `ansible/inventory.ini` – прод-сервер, `ansible_user=deploy`
- `ansible/ansible.cfg` – `host_key_checking = False` (ssh-фингерпринт уже подтвержден вручную ранее)
- `ansible/deploy.yml` – playbook из двух задач:
    1. `copy` – кладет собранный jar в `/opt/web/app.jar`;
    2. `command: systemctl restart web` 

Права на файл (`web:web`, группа с доступом на запись) настроены заранее

Также была создана отдельная jenkins job `devops-test-task-ansible` (все настройки такие же как и в предыдущей джобе соответственно) 

### Деплой без даунтайма

Пункты задания: zero-downtime обновление, readiness-проверка перед переключением трафика, быстрый откат. все реализовано как **третий отдельный** pipeline поверх уже существующих

### Идея

Одновременно на проде работают два экземпляра приложения:

| Цвет | systemd-юнит | Порт | jar |
| --- | --- | --- | --- |
| blue | `web` (без изменений) | 8080 | `/opt/web/app.jar` |
| green | `web-green` (новый) | 8081 | `/opt/web-green/app.jar` |

В любой момент трафик идет только на один из них. Деплой = выкатить новую версию во ВТОРОЙ (неактивный) экземпляр, дождаться, что он сам готов (`/actuator/health/readiness`), и только потом переключить nginx на него. старый экземпляр остается “резервным” для быстрого отката (остается работать)

### Инфраструктура

Новый systemd-юнит `/etc/systemd/system/web-green.service` – копия `web.service`, но со своим портом и путем (файл приложен на гитхаб)

Переключение реализовано через отдельный nginx-upstream, который перезаписывает деплой:

`/etc/nginx/conf.d/app_upstream.conf`:

```
upstream app_web {
    server 127.0.0.1:8080;
}
```

Server-блок для 8090 прописан отдельно, для теста (файл приложен на гитхаб)

Переключение “цвета” = переписать один файл `app_upstream.conf` + релоад nginx (graceful reload не роняет уже открытые соединения, zero-downtime).

Как проверяла: при остановленном `web-green` и upstream, указывающим на его порт (8081), `curl` возвращал `502 Bad Gateway`, это значило что nginx действительно роутит согласно конфигу

### Ansible playbooks

- `ansible/deploy-bluegreen.yml`:
    1. читает `app_upstream.conf`, определяет текущий активный “цвет”
    2. копирует новый jar в НЕактивный цвет
    3. перезапускает его systemd-юнит
    4. ждет его собственную readiness напрямую на его порту (readiness-гейт перед переключением)
    5. переписывает `app_upstream.conf` на порт нового цвета;
    6. `nginx -t` + `systemctl reload nginx` – переключение трафика;
    7. финальная проверка через сам nginx (порт 8090)
- `ansible/rollback-bluegreen.yml` – то же самое, но в обратную сторону: поднимает предыдущий цвет (на случай, если он почему-то не был живым), дожидается его readiness и переключает nginx обратно. Тоже с readiness-гейтом, а не слепым переключением.

Тут, кстати, в отличие от предыдущих плейбуков пришлось писать sudo, так как become не сработал. Достаточно интересный кейс, не встречала раннее: become “завернул” выполнение в пайтон скрипт, а такую команду sudousers правило не узнавало (считывало как запуск самого питона). но все равно, даже с sudo, все еще остаюсь в рамках задагия

(Ну и тоже отдала доп права на прод-сервере перед запуском)

### Jenkins

`Jenkinsfile.bluegreen`, job `devops-test-task-bluegreen`, с параметром `ACTION`:

- `deploy` (по дефолту) – Checkout → Build → deploy-bluegreen.yml → Verify
- `rollback` – без пересборки, сразу rollback-bluegreen.yml → Verify
- 

так выглядит список всех существующих джоб
![Screenshot 2026-08-03 at 23.51.00.png](pictures/Screenshot%202026-08-03%20at%2023.51.00.png)

раскрываются опции
![Screenshot 2026-08-03 at 23.51.42.png](pictures/Screenshot%202026-08-03%20at%2023.51.42.png)
## Проверка после деплоя

```
curl -i http://139.100.237.220/
```

проверка живости:

```
curl http://139.100.237.220/actuator/health/readiness
```

## Заключение

Могу сказать, что мне очень понравился jenkins. выглядит очень логичным и будто больше возможности для разных реализации. разные опции при запуске джобы понравились аж самой, восторг) конечно, не без приключений - самая первая джоба прогналась раза с 6-го, но оно того стоило!

Меньше всего ошибок / проблем было с частью nginx или systemd-сервисов. с ансиблом, кстати, тоже более менее гладко все прошло, но тут наоборот: ощущение, что можно было еще покрутить, чтобы сделать поинтереснее. в общем, ансибла мне в хорошем смысле не хватило :)
![Screenshot 2026-08-03 at 23.51.49.png](pictures/Screenshot%202026-08-03%20at%2023.51.49.png)