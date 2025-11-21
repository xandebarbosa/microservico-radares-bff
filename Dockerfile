# Etapa 1: Build da aplicação com Maven
FROM maven:3.9.6-eclipse-temurin-21 AS build
WORKDIR /app
# 1) Copia apenas o pom.xml para gerar cache
COPY pom.xml .
# 2) Gera cache baixando dependências ANTES do código
RUN mvn -B dependency:go-offline -DskipTests
# 3) Agora copia o restante do código
COPY src ./src
# 4) Build real
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
