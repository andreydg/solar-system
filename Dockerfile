FROM node:22-alpine AS frontend-deps

WORKDIR /app
COPY package*.json ./
RUN npm ci

FROM frontend-deps AS frontend-build

COPY . .
RUN npm run build

FROM maven:3.9-eclipse-temurin-21 AS backend-build

WORKDIR /app/backend
COPY backend/pom.xml ./
RUN mvn -B dependency:go-offline
COPY backend/src ./src
COPY --from=frontend-build /app/dist ./src/main/resources/static
RUN mvn -B package -DskipTests

FROM eclipse-temurin:21-jre-alpine AS runtime

ENV PORT=8080
WORKDIR /app
COPY --from=backend-build /app/backend/target/solar-system-backend-0.1.0.jar ./app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
