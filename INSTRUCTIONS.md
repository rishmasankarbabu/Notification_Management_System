# Setup and Run

This guide starts the local Notification Board using Bash. Run each
application in its own terminal and leave the terminals open while using it.

## Requirements

- Java 21 and Maven
- Docker Desktop with Docker Compose
- Node.js and npm

The local services use ports `5432` (PostgreSQL), `61616` (Artemis),
`8080` (backend), and `5173` (frontend). Make sure these ports are available.

## 1. Start PostgreSQL

From the repository root, open a terminal:

```bash
cd backend
docker compose up -d
docker compose ps
```

Compose starts PostgreSQL 17 and stores its files in a named volume.

## 2. Start the alert simulator

Open another terminal from the repository root:

```bash
cd alert-simulator
mvn spring-boot:run
```

The simulator starts an Artemis broker on `localhost:61616` and sends a
notification message immediately, then every 15-45 seconds.

## 3. Start the backend

Open another terminal from the repository root. Set the database
and operator credentials in this terminal before starting Spring Boot:

```bash
cd backend
$env:DB_PASSWORD = "admin123"
$env:APP_AUTH_PASSWORD = "operator123"
mvn spring-boot:run
```

`admin123` matches the local PostgreSQL password in `docker-compose.yaml`.
Choose your own local operator password. The backend connects to the simulator
at `tcp://localhost:61616` by default.

When startup completes, the API is available at `http://localhost:8080`.
Health check: `http://localhost:8080/actuator/health`.
API documentation: `http://localhost:8080/swagger-ui.html`.

## 4. Start the frontend

Open another terminal from the repository root:

```bash
cd frontend
npm install
npm run dev
```

Open `http://localhost:5173` and sign in with the operator username and
password configured for the backend. New notifications arrive from the simulator through JMS. Vite proxies
`/api` requests to `http://localhost:8080` by default. To use another backend
address, run the following in the frontend terminal instead of the final
`npm run dev` command above:

```bash
export VITE_API_PROXY_TARGET="http://localhost:8081"
npm run dev
```

## Stop the applications

Press `Ctrl+C` in each application terminal. To stop PostgreSQL while keeping
its data, run this from the repository root:

```bash
cd backend
docker compose down
```

To also delete the local database data, use `docker compose down -v` instead.
