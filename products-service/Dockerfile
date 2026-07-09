FROM eclipse-temurin:17-jdk-alpine as builder

RUN apk add --no-cache maven

WORKDIR /build

# Copia primeiro os POMs (parent compartilhado + do serviço) para cache de dependências
COPY pom.xml pom.xml
RUN mvn install -N

# correlation-commons (docs/architecture/observability.md): shared
# CorrelationId/RequestId/MessageId tracing infra, installed once per image
# build ahead of the service itself -- not a reactor module of the parent
# (ADR-0016), just a sibling Maven project resolved as a normal dependency.
COPY correlation-commons/pom.xml correlation-commons/pom.xml
RUN cd correlation-commons && mvn dependency:go-offline
COPY correlation-commons/src correlation-commons/src
RUN cd correlation-commons && mvn install -DskipTests

COPY products-service/pom.xml products-service/pom.xml
RUN cd products-service && mvn dependency:go-offline -B

# Copia o código fonte
COPY products-service/src products-service/src

# Build do projeto
RUN cd products-service && mvn clean package -DskipTests

# Stage final
FROM eclipse-temurin:17-jre-alpine

# Instala wait-for-it para aguardar dependências
RUN apk add --no-cache bash postgresql-client

WORKDIR /app

# Copia o JAR built
COPY --from=builder /build/products-service/target/*.jar app.jar

# Cria usuário não-root para segurança
RUN addgroup -S spring && adduser -S spring -G spring
USER spring

EXPOSE 8082

# Health check
HEALTHCHECK --interval=30s --timeout=3s --start-period=90s --retries=3 \
    CMD wget --no-verbose --tries=1 --spider http://localhost:8082/health || exit 1

ENTRYPOINT ["java", "-jar", "/app/app.jar"]