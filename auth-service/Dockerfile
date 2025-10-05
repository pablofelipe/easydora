FROM eclipse-temurin:17-jdk-alpine as builder

RUN apk add --no-cache maven

WORKDIR /app

# Copia primeiro o POM para cache de dependências
COPY pom.xml .

# Baixa dependências (cache layer)
RUN mvn dependency:go-offline

# Copia o código fonte
COPY src ./src

# Build do projeto
RUN mvn clean package -DskipTests

# Stage final
FROM eclipse-temurin:17-jdk-alpine
VOLUME /tmp
COPY --from=builder /app/target/auth-service-0.0.1-SNAPSHOT.jar app.jar

EXPOSE 8081

ENTRYPOINT ["java","-jar","/app.jar"]