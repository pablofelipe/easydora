FROM eclipse-temurin:17-jdk-alpine as builder

RUN apk add --no-cache maven

WORKDIR /app

# Copia primeiro o POM para cache de dependências
COPY pom.xml .

# Baixa dependências (cache layer)
RUN mvn dependency:go-offline -B

# Copia o código fonte
COPY src ./src

# Build do projeto
RUN mvn clean package -DskipTests

# Stage final
FROM eclipse-temurin:17-jre-alpine

# Instala wait-for-it para aguardar dependências
RUN apk add --no-cache bash postgresql-client

WORKDIR /app

# Copia o JAR built
COPY --from=builder /app/target/*.jar app.jar

# Cria usuário não-root para segurança
RUN addgroup -S spring && adduser -S spring -G spring
USER spring

EXPOSE 8082

# Health check
HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
    CMD wget --no-verbose --tries=1 --spider http://localhost:8082/health || exit 1

ENTRYPOINT ["java", "-jar", "/app/app.jar"]