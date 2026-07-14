# Dating Workspace

This is the dating app backend workspace containing:

- `dating-server/` - Java microservice monorepo
- `proto/` - gRPC interface contracts
- `ai-chat/` - Python AI chat service

## Services

| Service | Port | gRPC Port | Description |
|---------|------|-----------|-------------|
| mobile-gateway | 8080 | 19080 | BFF gateway |
| user-service | 18081 | 19081 | User profiles |
| im-service | 18082 | 19082 | IM orchestration |
| match-service | 18083 | 19083 | Matching engine |
| post-service | 18084 | 19084 | Posts & feed |
| payment-service | 18085 | 19085 | Payments |
| example-service | 18080 | 19090 | Template service |

## Quick Start

```bash
# Build a service
cd dating-server/example-service
mvn clean package

# Run with dev profile
SPRING_PROFILES_ACTIVE=dev mvn spring-boot:run
```

## Documentation

- [Student Developer Guide](./doc/student-dev-guide.md)
- [Development Onboarding](./doc/dev-onboarding.md)
