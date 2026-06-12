# Premisave Messenger Service

Real-time messaging service (WhatsApp-like) for Premisave platform.

## Features
- Real-time 1-on-1 and Group messaging (WebSocket + STOMP)
- Message types: Text, Image, Video, Voice, Location
- Read receipts, Typing indicators, Online status
- Delete message (for me / for everyone)
- Media upload (Cloudinary)
- Integration with Auth Service (port 8080)
- JWT Authentication
- MongoDB + Redis

## Tech Stack
- Spring Boot 4.0.1 + Java 21
- WebSocket / STOMP
- MongoDB
- Redis
- Feign Client (Auth Service)
- Cloudinary

## Running Locally

```bash
# 1. Start dependencies
docker-compose up -d mongo redis

# 2. Build
mvn clean package

# 3. Run
java -jar target/premisave-messenger-service-0.0.1-SNAPSHOT.jar
