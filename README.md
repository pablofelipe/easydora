Easydora - E-commerce Microservices
E-commerce project developed with microservices architecture.

🏗️ Architecture
API Gateway: Go + Gin

Auth Service: Spring Boot + JWT

Products Service: Spring Boot + PostgreSQL

Inventory Service: Go + PostgreSQL

Orders Service: Spring Boot + RabbitMQ

Billing Service: Spring Boot

Notification Service: FastAPI + RabbitMQ

Frontend: SvelteKit

🚀 Quick Start
bash
# Clone the repository
git clone <repository-url>
cd easydora

# Start all services
docker-compose up -d

# Check status
docker-compose ps
📍 Services & Endpoints
API Gateway: http://localhost:8080

Auth Service: http://localhost:8081

Products Service: http://localhost:8082

Inventory Service: http://localhost:8083

Orders Service: http://localhost:8084

Billing Service: http://localhost:8085

Notification Service: http://localhost:8086

Frontend: http://localhost:3000

RabbitMQ Management: http://localhost:15672 (admin/password)

PostgreSQL: localhost:5432 (admin/password)

🔧 Prerequisites
Make sure you have installed:

Docker Desktop (Windows/Mac) or Docker Engine (Linux)

Docker Compose

Git

Verify Docker Installation
bash
docker --version
docker-compose --version
🐛 Docker Troubleshooting (Windows)
If you encounter Docker connection issues:

1. Start Docker Desktop
Open Docker Desktop application

Wait for it to show "Docker Desktop is running"

2. Verify Docker Service
bash
# Check if Docker is running
docker version

# If this fails, Docker service is not running
3. Alternative Commands
If docker-compose doesn't work, try:

bash
# Use docker compose (without hyphen)
docker compose up -d postgres rabbitmq

# Or start Docker Desktop first, then use standard command
docker-compose up -d postgres rabbitmq
4. Restart Docker Service
Right-click Docker Desktop system tray icon

Select "Restart Docker Desktop"

Wait for it to fully start
