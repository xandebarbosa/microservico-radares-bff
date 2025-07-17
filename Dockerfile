# Etapa 1: Build da aplicação com Maven
FROM maven:3.9.6-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn clean package -DskipTests

# Etapa 2: Imagem final com JAR gerado
FROM eclipse-temurin:21-jdk
VOLUME /tmp
WORKDIR /app

# Copia o .jar gerado no estágio anterior para a imagem final
COPY --from=build /app/target/*.jar app.jar
# Expõe a porta em que a aplicação irá rodar dentro do contêiner
EXPOSE 8080
# Comando para iniciar a aplicação quando o contêiner for executado
ENTRYPOINT ["java", "-jar", "app.jar"]
