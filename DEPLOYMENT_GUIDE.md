# 🚀 UPI Offline Mesh - Deployment Guide

---

## Quick Start (Docker)

### Prerequisites
- Docker installed ([Download](https://www.docker.com/products/docker-desktop))
- Git (for cloning)
- That's it!

### Option 1: Deploy with Docker Compose (Recommended for Production)

**Step 1: Clone/Navigate to project**
```bash
cd c:\Users\prath\OneDrive\Desktop\projects.java\UPI_Without_Internet
```

**Step 2: Create .env file (for secure passwords)**
```bash
cp .env.example .env
# Edit .env and change passwords to strong ones
# DB_PASSWORD=YourSecurePassword123!
# PGADMIN_PASSWORD=YourAdminPassword!
```

**Step 3: Build and deploy**
```bash
docker-compose up -d
```

This will:
- Build the Java application into a Docker image
- Start PostgreSQL database
- Start the UPI Mesh backend
- Start pgAdmin (optional database UI)

**Step 4: Verify it's running**
```bash
docker-compose ps
```

Expected output:
```
NAME                    STATUS           PORTS
upi-mesh-db             Up (healthy)     5432->5432/tcp
upi-mesh-app            Up (healthy)     8080->8080/tcp
upi-mesh-pgadmin        Up               5050->80/tcp
```

**Step 5: Access the application**
- **Dashboard:** http://localhost:8080
- **API:** http://localhost:8080/api
- **Database Admin:** http://localhost:5050 (pgAdmin)
  - Email: `admin@example.com`
  - Password: (from .env PGADMIN_PASSWORD)

---

### Option 2: Build Docker Image Manually

**Step 1: Build the image**
```bash
docker build -t upi-mesh-backend:latest .
```

**Step 2: Run with separate PostgreSQL**
```bash
# Start PostgreSQL
docker run -d \
  --name upi-postgres \
  -e POSTGRES_DB=upimesh \
  -e POSTGRES_USER=upiuser \
  -e POSTGRES_PASSWORD=SecurePassword123! \
  -p 5432:5432 \
  postgres:15-alpine

# Start the app
docker run -d \
  --name upi-mesh-app \
  -e SPRING_DATASOURCE_URL=jdbc:postgresql://upi-postgres:5432/upimesh \
  -e SPRING_DATASOURCE_USERNAME=upiuser \
  -e SPRING_DATASOURCE_PASSWORD=SecurePassword123! \
  -p 8080:8080 \
  --link upi-postgres:postgres \
  upi-mesh-backend:latest
```

---

### Option 3: Push to Docker Hub & Deploy

**Step 1: Tag the image**
```bash
docker build -t yourusername/upi-mesh-backend:1.0 .
```

**Step 2: Push to Docker Hub**
```bash
docker login
docker push yourusername/upi-mesh-backend:1.0
```

**Step 3: Deploy anywhere Docker is available**
```bash
docker run -d \
  --name upi-mesh \
  -e SPRING_DATASOURCE_URL=jdbc:postgresql://your-postgres-server:5432/upimesh \
  -e SPRING_DATASOURCE_USERNAME=dbuser \
  -e SPRING_DATASOURCE_PASSWORD=dbpassword \
  -p 8080:8080 \
  yourusername/upi-mesh-backend:1.0
```

---

## Cloud Deployment Options

### Azure Deployment (App Service)

#### Option A: Azure Container Registry + App Service

**Step 1: Create Container Registry**
```bash
az acr create --resource-group myResourceGroup \
  --name myContainerRegistry \
  --sku Basic
```

**Step 2: Build and push image**
```bash
az acr build --registry myContainerRegistry \
  --image upi-mesh:latest .
```

**Step 3: Create App Service**
```bash
az appservice plan create --name myAppPlan \
  --resource-group myResourceGroup \
  --sku B2 --is-linux

az webapp create --resource-group myResourceGroup \
  --plan myAppPlan \
  --name upi-mesh-app \
  --deployment-container-image-name myContainerRegistry.azurecr.io/upi-mesh:latest
```

**Step 4: Configure database**
```bash
# Create Azure Database for PostgreSQL
az postgres server create \
  --resource-group myResourceGroup \
  --name upi-mesh-db \
  --location eastus \
  --admin-user dbadmin \
  --admin-password SecurePassword123! \
  --sku-name B_Gen5_2

# Configure environment variables in App Service
az webapp config appsettings set \
  --resource-group myResourceGroup \
  --name upi-mesh-app \
  --settings \
    SPRING_DATASOURCE_URL="jdbc:postgresql://upi-mesh-db.postgres.database.azure.com:5432/upimesh" \
    SPRING_DATASOURCE_USERNAME="dbadmin@upi-mesh-db" \
    SPRING_DATASOURCE_PASSWORD="SecurePassword123!"
```

**Step 5: Verify**
```bash
# Get the app URL
az webapp show --name upi-mesh-app \
  --resource-group myResourceGroup \
  --query defaultHostName
```

Visit: `https://upi-mesh-app.azurewebsites.net`

#### Option B: Azure Container Instances (Simpler, but stateless)

```bash
az container create \
  --resource-group myResourceGroup \
  --name upi-mesh \
  --image myContainerRegistry.azurecr.io/upi-mesh:latest \
  --cpu 2 --memory 4 \
  --registry-login-server myContainerRegistry.azurecr.io \
  --registry-username <acr-username> \
  --registry-password <acr-password> \
  --environment-variables \
    SPRING_DATASOURCE_URL="jdbc:postgresql://..." \
    SPRING_DATASOURCE_USERNAME="user" \
    SPRING_DATASOURCE_PASSWORD="pass" \
  --ports 8080 \
  --dns-name-label upi-mesh
```

---

### AWS Deployment (ECS + RDS)

**Step 1: Push to ECR**
```bash
# Create repository
aws ecr create-repository --repository-name upi-mesh-backend --region us-east-1

# Tag and push
docker tag upi-mesh-backend:latest \
  ACCOUNT_ID.dkr.ecr.us-east-1.amazonaws.com/upi-mesh-backend:latest

aws ecr get-login-password --region us-east-1 | docker login \
  --username AWS \
  --password-stdin ACCOUNT_ID.dkr.ecr.us-east-1.amazonaws.com

docker push ACCOUNT_ID.dkr.ecr.us-east-1.amazonaws.com/upi-mesh-backend:latest
```

**Step 2: Create RDS PostgreSQL Database**
```bash
aws rds create-db-instance \
  --db-instance-identifier upi-mesh-db \
  --db-instance-class db.t3.micro \
  --engine postgres \
  --master-username dbadmin \
  --master-user-password SecurePassword123! \
  --allocated-storage 20 \
  --db-name upimesh
```

**Step 3: Create ECS Task Definition**
```json
{
  "family": "upi-mesh-task",
  "networkMode": "awsvpc",
  "requiresCompatibilities": ["FARGATE"],
  "cpu": "256",
  "memory": "512",
  "containerDefinitions": [
    {
      "name": "upi-mesh-backend",
      "image": "ACCOUNT_ID.dkr.ecr.us-east-1.amazonaws.com/upi-mesh-backend:latest",
      "portMappings": [
        {
          "containerPort": 8080,
          "hostPort": 8080,
          "protocol": "tcp"
        }
      ],
      "environment": [
        {
          "name": "SPRING_DATASOURCE_URL",
          "value": "jdbc:postgresql://upi-mesh-db.xxxxx.us-east-1.rds.amazonaws.com:5432/upimesh"
        },
        {
          "name": "SPRING_DATASOURCE_USERNAME",
          "value": "dbadmin"
        }
      ],
      "secrets": [
        {
          "name": "SPRING_DATASOURCE_PASSWORD",
          "valueFrom": "arn:aws:secretsmanager:us-east-1:ACCOUNT_ID:secret:rds-password"
        }
      ]
    }
  ]
}
```

**Step 4: Create ECS Service**
```bash
aws ecs create-service \
  --cluster upi-mesh-cluster \
  --service-name upi-mesh-service \
  --task-definition upi-mesh-task \
  --desired-count 2 \
  --launch-type FARGATE \
  --network-configuration "awsvpcConfiguration={subnets=[subnet-xxx],securityGroups=[sg-xxx],assignPublicIp=ENABLED}" \
  --load-balancers targetGroupArn=arn:aws:elasticloadbalancing:...,containerName=upi-mesh-backend,containerPort=8080
```

---

### Kubernetes (Most Production-Ready)

**Create k8s deployment files:**

```yaml
# deployment.yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: upi-mesh-backend
spec:
  replicas: 3
  selector:
    matchLabels:
      app: upi-mesh
  template:
    metadata:
      labels:
        app: upi-mesh
    spec:
      containers:
      - name: upi-mesh-backend
        image: your-registry.azurecr.io/upi-mesh-backend:latest
        ports:
        - containerPort: 8080
        env:
        - name: SPRING_DATASOURCE_URL
          valueFrom:
            configMapKeyRef:
              name: db-config
              key: url
        - name: SPRING_DATASOURCE_USERNAME
          valueFrom:
            secretKeyRef:
              name: db-credentials
              key: username
        - name: SPRING_DATASOURCE_PASSWORD
          valueFrom:
            secretKeyRef:
              name: db-credentials
              key: password
        livenessProbe:
          httpGet:
            path: /api/accounts
            port: 8080
          initialDelaySeconds: 30
          periodSeconds: 10
        readinessProbe:
          httpGet:
            path: /api/accounts
            port: 8080
          initialDelaySeconds: 5
          periodSeconds: 5
        resources:
          requests:
            cpu: 250m
            memory: 512Mi
          limits:
            cpu: 500m
            memory: 1Gi
---
apiVersion: v1
kind: Service
metadata:
  name: upi-mesh-service
spec:
  type: LoadBalancer
  ports:
  - port: 80
    targetPort: 8080
  selector:
    app: upi-mesh
---
apiVersion: v1
kind: ConfigMap
metadata:
  name: db-config
data:
  url: "jdbc:postgresql://postgres:5432/upimesh"
---
apiVersion: v1
kind: Secret
metadata:
  name: db-credentials
type: Opaque
stringData:
  username: upiuser
  password: SecurePassword123!
```

**Deploy:**
```bash
kubectl apply -f deployment.yaml
kubectl get svc  # Get LoadBalancer IP
```

---

## Monitoring & Maintenance

### Check Logs
```bash
# Docker Compose
docker-compose logs -f upi-mesh-app

# Single container
docker logs -f upi-mesh-app

# Kubernetes
kubectl logs deployment/upi-mesh-backend -f
```

### Database Backups
```bash
# PostgreSQL dump
docker exec upi-mesh-db pg_dump -U upiuser upimesh > backup.sql

# Restore
docker exec -i upi-mesh-db psql -U upiuser upimesh < backup.sql
```

### Scale Up/Down
```bash
# Docker Compose (single server, so limited scaling)
docker-compose up -d --scale upi-mesh-app=3  # Won't work with port binding

# Kubernetes
kubectl scale deployment upi-mesh-backend --replicas=5
```

### Health Checks
```bash
# Via API
curl http://localhost:8080/api/accounts

# Via Docker
docker inspect --format='{{.State.Health.Status}}' upi-mesh-app

# Via Kubernetes
kubectl get pods
```

---

## Production Checklist

- [ ] Use strong, unique passwords (not defaults from .env.example)
- [ ] Enable HTTPS/TLS (use nginx reverse proxy or cloud load balancer)
- [ ] Configure database backups (daily, replicated)
- [ ] Set up monitoring & alerting (logs, metrics, uptime)
- [ ] Enable database user with least privilege (not full admin)
- [ ] Rotate RSA encryption keys periodically
- [ ] Use managed PostgreSQL (Azure Database for PostgreSQL, AWS RDS)
- [ ] Enable audit logging (who did what, when)
- [ ] Set resource limits (CPU, memory, disk)
- [ ] Configure auto-recovery (restart on crash)
- [ ] Use environment variables or secrets manager (not hardcoded)
- [ ] Test disaster recovery (backup & restore)
- [ ] Set up CI/CD pipeline (auto-deploy on git push)

---

## Quick Troubleshooting

### Container won't start
```bash
docker-compose logs upi-mesh-app
# Check for port conflicts, missing dependencies, or config errors
```

### Database connection fails
```bash
# Test connection
docker exec upi-mesh-db psql -U upiuser -d upimesh -c "SELECT 1"

# Check environment variables
docker inspect upi-mesh-app | grep -A 20 "Env"
```

### Out of disk space
```bash
docker system prune -a  # Remove unused images/containers
docker volume prune     # Remove unused volumes
```

### Port already in use
```bash
# Windows
netstat -ano | findstr :8080

# Linux/Mac
lsof -i :8080

# Kill process
kill -9 <PID>
```

---

## Summary

| Method | Ease | Scalability | Cost | Best For |
|--------|------|-------------|------|----------|
| Docker Compose | ⭐⭐⭐⭐⭐ | Single machine | Free | Development, demo |
| Azure App Service | ⭐⭐⭐⭐ | Auto-scaling | Medium | Small teams |
| Kubernetes | ⭐⭐ | Unlimited | Low-high | Large deployments |
| AWS ECS | ⭐⭐⭐ | Auto-scaling | Low | AWS ecosystem |

**Recommendation:** Start with Docker Compose locally, then move to Azure App Service or Kubernetes for production.

