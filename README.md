# Looped

**A workplace-verified social app for authentic company conversations**

## 🚀 Quick Start Commands

**Project Requirements**: Java 25, Spring Boot 3.5.6

```bash
# Compile the project
./mvnw clean compile

# Build JAR files (automatically cleans first)
./mvnw clean package

# Run the application
./mvnw spring-boot:run

# Run tests
./mvnw test

# Build without tests
./mvnw clean package -DskipTests

# Clean and rebuild everything
./mvnw clean install

# Note: Maven automatically handles cleaning when needed.
# Unlike C++ 'make clean', you don't need to manually clean
# after compilation unless you want to force a full rebuild.
```

Looped is a pseudonymous social platform where employees verify their workplace affiliation and engage in real-time discussions within their company's private channel. Think "YikYak for any employer" – fostering authentic workplace conversations while maintaining privacy and professional boundaries.

## 🎯 Project Overview

Looped enables workplace-verified social interactions through:

- **Company Verification**: Multi-layered verification system (LinkedIn, email domain, HR integration, manual review)
- **Pseudonymous Communication**: Real names not required - focus on authentic conversation
- **Real-time Engagement**: Live feed updates, instant posting, light reactions
- **Privacy-First**: Minimal data collection, no PII in logs, secure by design
- **Content Moderation**: Clear reporting paths and automated moderation tools

## 🏗️ Technical Architecture

### Backend Services
- **Framework**: Spring Boot 3.5.6 with Java 25
- **Database**: Neon PostgreSQL (MVP) → AWS RDS (Production)
- **API Gateway**: AWS API Gateway with Lambda integration
- **Real-time**: WebSocket API for live feed updates
- **Authentication**: AWS Cognito + custom company verification

### Microservices Structure

```
looped-services/
├── posting/          # Create, edit, delete posts
├── feed/            # Company feeds, real-time updates, content delivery
├── auth/            # User verification, company validation, session management
├── websocket/       # Real-time notifications, live updates
├── moderation/      # Reports, content flagging, automated moderation
└── shared/          # Common utilities, DTOs, exceptions, configurations
```

## 🛠️ Technology Stack

### Core Technologies
- **Java 25** - Latest version with modern language features
- **Spring Boot 3.5.6** - Enterprise-grade framework
  - Spring Web - RESTful API development
  - Spring Data JPA - Database abstraction layer
  - Spring Security - Authentication and authorization
  - Spring WebSocket - Real-time communication
- **Maven** - Dependency management and build automation
- **PostgreSQL** - Robust relational database

### Cloud & Infrastructure
- **AWS API Gateway** - API management and routing
- **AWS Lambda** - Serverless compute integration
- **AWS Cognito** - User identity and access management
- **Neon PostgreSQL** - Serverless database (MVP phase)
- **WebSocket** - Real-time bidirectional communication

## 🚀 Getting Started

### Prerequisites

- **Java 25** ([Download here](https://adoptium.net/))
- **Maven 3.8+** (included via wrapper)
- **Git** for version control
- **Internet connection** for dependencies

### Installation & Setup

1. **Clone the repository**
   ```bash
   git clone https://github.com/your-org/looped-services.git
   cd looped-services
   ```

2. **Verify Java installation**
   ```bash
   java -version
   # Should show Java 25
   ```

3. **Build all services**
   ```bash
   ./mvnw clean install
   ```

4. **Set up environment variables**
   ```bash
   cp .env.example .env
   # Edit .env with your configuration values
   ```

### Environment Configuration

Create a `.env` file in the root directory:

```env
# Database Configuration
DB_HOST=localhost
DB_PORT=5432
DB_NAME=looped_dev
DB_USERNAME=your_username
DB_PASSWORD=your_password

# AWS Configuration
AWS_REGION=us-east-1
AWS_ACCESS_KEY_ID=your_access_key
AWS_SECRET_ACCESS_KEY=your_secret_key

# Cognito Configuration
COGNITO_USER_POOL_ID=your_pool_id
COGNITO_CLIENT_ID=your_client_id

# Application Configuration
SERVER_PORT=8080
JWT_SECRET=your_jwt_secret
WEBSOCKET_PORT=8081
```

### Running the Application

#### Development Mode (All Services)
```bash
# Start all services concurrently
./mvnw spring-boot:run -f posting/pom.xml &
./mvnw spring-boot:run -f feed/pom.xml &
./mvnw spring-boot:run -f auth/pom.xml &
./mvnw spring-boot:run -f websocket/pom.xml &
./mvnw spring-boot:run -f moderation/pom.xml &
```

#### Individual Service
```bash
# Run specific service
cd posting
../mvnw spring-boot:run
```

#### Production Mode
```bash
# Build and run JAR files
./mvnw clean package
java -jar posting/target/posting-1.0.0.jar
```

## 📋 API Endpoints Documentation

### Authentication Service (`/auth`)
```
POST   /api/auth/register          # User registration
POST   /api/auth/login             # User authentication
POST   /api/auth/verify-company    # Company verification
GET    /api/auth/profile           # User profile
PUT    /api/auth/profile           # Update profile
DELETE /api/auth/account           # Delete account
```

### Posting Service (`/posting`)
```
POST   /api/posts                  # Create new post
GET    /api/posts/{id}             # Get specific post
PUT    /api/posts/{id}             # Update post
DELETE /api/posts/{id}             # Delete post
POST   /api/posts/{id}/react       # Add reaction
DELETE /api/posts/{id}/react       # Remove reaction
```

### Feed Service (`/feed`)
```
GET    /api/feed/company/{id}      # Get company feed
GET    /api/feed/trending          # Get trending posts
GET    /api/feed/recent            # Get recent posts
POST   /api/feed/refresh           # Refresh feed
```

### WebSocket Service (`/websocket`)
```
WS     /ws/feed                    # Real-time feed updates
WS     /ws/notifications           # User notifications
WS     /ws/company/{id}            # Company-specific updates
```

### Moderation Service (`/moderation`)
```
POST   /api/reports                # Report content
GET    /api/reports                # Get reports (admin)
PUT    /api/reports/{id}/resolve   # Resolve report
POST   /api/moderation/flag        # Flag content
GET    /api/moderation/queue       # Moderation queue
```

## 🗄️ Database Schema Overview

### Core Tables

#### Users
```sql
users (
  id UUID PRIMARY KEY,
  cognito_id VARCHAR UNIQUE,
  company_id UUID REFERENCES companies(id),
  verification_status VARCHAR,
  created_at TIMESTAMP,
  updated_at TIMESTAMP
)
```

#### Companies
```sql
companies (
  id UUID PRIMARY KEY,
  name VARCHAR NOT NULL,
  domain VARCHAR UNIQUE,
  verification_method VARCHAR,
  created_at TIMESTAMP
)
```

#### Posts
```sql
posts (
  id UUID PRIMARY KEY,
  user_id UUID REFERENCES users(id),
  company_id UUID REFERENCES companies(id),
  content TEXT NOT NULL,
  reactions_count INTEGER DEFAULT 0,
  created_at TIMESTAMP,
  updated_at TIMESTAMP
)
```

#### Reactions
```sql
reactions (
  id UUID PRIMARY KEY,
  user_id UUID REFERENCES users(id),
  post_id UUID REFERENCES posts(id),
  reaction_type VARCHAR,
  created_at TIMESTAMP,
  UNIQUE(user_id, post_id)
)
```

## 🧪 Testing Strategy

### Unit Tests
```bash
# Run all unit tests
./mvnw test

# Run tests for specific service
./mvnw test -f posting/pom.xml

# Run with coverage
./mvnw test jacoco:report
```

### Integration Tests
```bash
# Run integration tests
./mvnw verify -P integration-tests

# Run with test containers
./mvnw verify -P testcontainers
```

### API Testing
```bash
# Using provided Postman collection
newman run tests/api/looped-api-tests.json

# Using curl scripts
./scripts/test-api.sh
```

## 🚀 Deployment Instructions

### Docker Deployment
```bash
# Build Docker images
docker-compose build

# Run services
docker-compose up -d

# Scale specific service
docker-compose up -d --scale posting=3
```

### AWS Lambda Deployment
```bash
# Package for Lambda
./mvnw clean package -P lambda

# Deploy with SAM
sam build
sam deploy --guided
```

### Traditional Server Deployment
```bash
# Build production JARs
./mvnw clean package -P production

# Copy to server and run
scp target/*.jar user@server:/opt/looped/
ssh user@server 'systemctl restart looped-*'
```

## 🔧 Development Workflow

### Code Quality
- **Checkstyle**: Enforced code formatting
- **SpotBugs**: Static analysis for bug detection
- **JaCoCo**: Code coverage reporting (minimum 80%)
- **SonarQube**: Continuous code quality inspection

### Pre-commit Hooks
```bash
# Install pre-commit hooks
./scripts/install-hooks.sh

# Manual run
./scripts/pre-commit-check.sh
```

## 🤝 Contributing Guidelines

### Getting Started
1. Fork the repository
2. Create a feature branch: `git checkout -b feature/your-feature`
3. Follow coding standards and write tests
4. Ensure all tests pass: `./mvnw verify`
5. Submit a pull request with clear description

### Coding Standards
- Follow [Google Java Style Guide](https://google.github.io/styleguide/javaguide.html)
- Write meaningful commit messages
- Include unit tests for new features
- Update documentation for public APIs
- Maintain backward compatibility

### Pull Request Process
1. Update README.md if needed
2. Add tests for new functionality
3. Ensure CI/CD pipeline passes
4. Request review from maintainers
5. Address feedback promptly

## 🔒 Security & Privacy

### Data Protection
- **No PII in logs**: Strict policy against logging personal information
- **Minimal data collection**: Only essential data stored
- **Encryption**: All data encrypted in transit and at rest
- **Regular audits**: Quarterly security assessments

### Authentication Flow
1. User signs up via AWS Cognito
2. Company verification through multiple channels
3. JWT tokens for API authentication
4. Refresh token rotation for security

## 📊 Monitoring & Observability

### Application Metrics
- **Spring Boot Actuator**: Health checks and metrics
- **Micrometer**: Application metrics collection
- **Custom dashboards**: Business-specific monitoring

### Logging
- **Structured logging**: JSON format with correlation IDs
- **Log levels**: Configurable per environment
- **Centralized logs**: ELK stack integration ready

## 🚦 Environment-Specific Configuration

### Development
```yaml
spring:
  profiles:
    active: dev
  datasource:
    url: jdbc:postgresql://localhost:5432/looped_dev
logging:
  level:
    com.looped: DEBUG
```

### Production
```yaml
spring:
  profiles:
    active: prod
  datasource:
    url: ${DATABASE_URL}
logging:
  level:
    com.looped: INFO
```

## 📈 Roadmap

### Phase 1 (MVP) - Q1 2024
- ✅ Core posting functionality
- ✅ Basic company verification
- ✅ Real-time feed updates
- ✅ Content moderation system

### Phase 2 (Growth) - Q2 2024
- 🔄 Advanced verification methods
- 🔄 Enhanced moderation AI
- 🔄 Mobile app integration
- 🔄 Analytics dashboard

### Phase 3 (Scale) - Q3 2024
- ⏳ Multi-region deployment
- ⏳ Advanced recommendation engine
- ⏳ Enterprise features
- ⏳ White-label solutions

## 📞 Support & Contact

- **Issues**: [GitHub Issues](https://github.com/your-org/looped-services/issues)
- **Documentation**: [Wiki](https://github.com/your-org/looped-services/wiki)
- **Security**: security@looped.app
- **General**: support@looped.app

## 📄 License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

---

**Built with ❤️ by the Looped Team**

*Creating authentic workplace conversations, one company at a time.*