FROM eclipse-temurin:17-jdk-alpine as builder

RUN apk add --no-cache maven

WORKDIR /build

# Copia primeiro os POMs (parent compartilhado + do serviço) para cache de dependências
COPY pom.xml pom.xml
COPY auth-service/pom.xml auth-service/pom.xml
RUN cd auth-service && mvn dependency:go-offline

# Copia o código fonte
COPY auth-service/src auth-service/src

# Build do projeto
RUN cd auth-service && mvn clean package -DskipTests

# Stage final
FROM eclipse-temurin:17-jdk-alpine
VOLUME /tmp
COPY --from=builder /build/auth-service/target/auth-service-0.0.1-SNAPSHOT.jar app.jar

EXPOSE 8081

HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
    CMD wget --no-verbose --tries=1 --spider http://localhost:8081/health || exit 1

ENTRYPOINT ["java","-jar","/app.jar"]