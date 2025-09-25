FROM eclipse-temurin:17-jdk-alpine

VOLUME /tmp

# Copia o JAR para o container
COPY target/auth-service-0.0.1-SNAPSHOT.jar app.jar

# Expõe a porta da aplicação
EXPOSE 8081

# Comando para executar a aplicação
ENTRYPOINT ["java","-jar","/app.jar"]