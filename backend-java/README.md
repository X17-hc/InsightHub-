# InsightHub Backend（Java）

Spring Boot 3 + JDK 21。第 1 周职责：对外研究 API、WebClient 调 Python、任务/事件/报告落库。

```powershell
cd C:\Users\Dell\PycharmProjects\PythonTestProject\InsightHub\backend-java
mvn -DskipTests spring-boot:run
```

- 健康检查：`GET http://127.0.0.1:8080/api/v1/health`
- 创建任务：`POST http://127.0.0.1:8080/api/v1/research/tasks` body `{"query":"..."}`
