FROM eclipse-temurin:17-jdk-alpine as builder

RUN apk add --no-cache maven

WORKDIR /build

# Copia primeiro os POMs (parent compartilhado + do serviço) para cache de dependências
COPY pom.xml pom.xml
COPY orders-service/pom.xml orders-service/pom.xml
RUN cd orders-service && mvn dependency:go-offline -B

# Copia o código fonte
COPY orders-service/src orders-service/src

# Build do projeto
RUN cd orders-service && mvn clean package -DskipTests

# Stage final
FROM eclipse-temurin:17-jre-alpine

# Instala wait-for-it para aguardar dependências
RUN apk add --no-cache bash postgresql-client

WORKDIR /app

# Copia o JAR built
COPY --from=builder /build/orders-service/target/*.jar app.jar

# Cria usuário não-root para segurança
RUN addgroup -S spring && adduser -S spring -G spring
USER spring

EXPOSE 8084

# Health check
HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
    CMD wget --no-verbose --tries=1 --spider http://localhost:8084/health || exit 1

ENTRYPOINT ["java", "-jar", "/app/app.jar"]