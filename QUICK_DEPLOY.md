# 🚀 Quick Deployment Commands

## **FASTEST: Docker Compose (30 seconds)**

```bash
# 1. Navigate to project
cd c:\Users\prath\OneDrive\Desktop\projects.java\UPI_Without_Internet

# 2. Create .env (password config)
cp .env.example .env

# 3. Deploy (builds image + starts all services)
docker-compose up -d

# 4. Check status
docker-compose ps

# 5. View logs
docker-compose logs -f upi-mesh-app

# 6. Access
#    - App: http://localhost:8080
#    - DB Admin: http://localhost:5050
```

---

## **PRODUCTION BUILD: Build Docker Image**

```bash
# Build image locally
docker build -t upi-mesh-backend:latest .

# Run with PostgreSQL
docker run -d \
  --name upi-pg \
  -e POSTGRES_DB=upimesh \
  -e POSTGRES_USER=upiuser \
  -e POSTGRES_PASSWORD=YourPassword123! \
  -p 5432:5432 \
  postgres:15-alpine

docker run -d \
  --name upi-mesh \
  -e SPRING_DATASOURCE_URL=jdbc:postgresql://upi-pg:5432/upimesh \
  -e SPRING_DATASOURCE_USERNAME=upiuser \
  -e SPRING_DATASOURCE_PASSWORD=YourPassword123! \
  -p 8080:8080 \
  --link upi-pg:postgres \
  upi-mesh-backend:latest

# Check it's running
curl http://localhost:8080/api/accounts
```

---

## **PUSH TO REGISTRY (Docker Hub)**

```bash
# Login
docker login

# Tag
docker tag upi-mesh-backend:latest your-username/upi-mesh-backend:1.0

# Push
docker push your-username/upi-mesh-backend:1.0

# Pull and run anywhere
docker run -d \
  -e SPRING_DATASOURCE_URL=jdbc:postgresql://your-db-server:5432/upimesh \
  -e SPRING_DATASOURCE_USERNAME=dbuser \
  -e SPRING_DATASOURCE_PASSWORD=dbpass \
  -p 8080:8080 \
  your-username/upi-mesh-backend:1.0
```

---

## **AZURE DEPLOYMENT (Container Registry + App Service)**

```bash
# 1. Create resource group
az group create -n myResourceGroup -l eastus

# 2. Create container registry
az acr create -g myResourceGroup -n myRegistry --sku Basic

# 3. Build and push to ACR
az acr build -r myRegistry -t upi-mesh:latest .

# 4. Create App Service Plan
az appservice plan create \
  -g myResourceGroup \
  -n myAppPlan \
  --sku B2 --is-linux

# 5. Create Web App
az webapp create \
  -g myResourceGroup \
  -p myAppPlan \
  -n upi-mesh-app \
  -i myRegistry.azurecr.io/upi-mesh:latest

# 6. Create PostgreSQL Database
az postgres server create \
  -g myResourceGroup \
  -n upi-mesh-db \
  --admin-user dbadmin \
  --admin-password YourPassword123! \
  --sku-name B_Gen5_2

# 7. Configure app settings
az webapp config appsettings set \
  -g myResourceGroup \
  -n upi-mesh-app \
  --settings \
    SPRING_DATASOURCE_URL="jdbc:postgresql://upi-mesh-db.postgres.database.azure.com:5432/upimesh" \
    SPRING_DATASOURCE_USERNAME="dbadmin@upi-mesh-db" \
    SPRING_DATASOURCE_PASSWORD="YourPassword123!"

# 8. Get URL
az webapp show -g myResourceGroup -n upi-mesh-app --query defaultHostName

# Visit: https://upi-mesh-app.azurewebsites.net
```

---

## **AWS DEPLOYMENT (ECR + ECS + RDS)**

```bash
# 1. Create ECR repository
aws ecr create-repository --repository-name upi-mesh --region us-east-1

# 2. Push image
aws ecr get-login-password --region us-east-1 | docker login \
  --username AWS --password-stdin ACCOUNT.dkr.ecr.us-east-1.amazonaws.com

docker tag upi-mesh-backend:latest \
  ACCOUNT.dkr.ecr.us-east-1.amazonaws.com/upi-mesh:latest

docker push ACCOUNT.dkr.ecr.us-east-1.amazonaws.com/upi-mesh:latest

# 3. Create RDS PostgreSQL
aws rds create-db-instance \
  --db-instance-identifier upi-mesh-db \
  --db-instance-class db.t3.micro \
  --engine postgres \
  --master-username dbadmin \
  --master-user-password YourPassword123! \
  --allocated-storage 20 \
  --db-name upimesh

# 4. Create ECS cluster
aws ecs create-cluster --cluster-name upi-mesh-cluster

# 5. Register task definition (see DEPLOYMENT_GUIDE.md for full JSON)
aws ecs register-task-definition --cli-input-json file://task-definition.json

# 6. Create service
aws ecs create-service \
  --cluster upi-mesh-cluster \
  --service-name upi-mesh-service \
  --task-definition upi-mesh-task:1 \
  --desired-count 2 \
  --launch-type FARGATE \
  --network-configuration "awsvpcConfiguration={subnets=[subnet-xxx],securityGroups=[sg-xxx],assignPublicIp=ENABLED}"

# Check status
aws ecs describe-services --cluster upi-mesh-cluster --services upi-mesh-service
```

---

## **KUBERNETES DEPLOYMENT**

```bash
# 1. Create ConfigMap for database config
kubectl create configmap db-config --from-literal=url="jdbc:postgresql://..."

# 2. Create Secret for credentials
kubectl create secret generic db-credentials \
  --from-literal=username=upiuser \
  --from-literal=password=YourPassword123!

# 3. Deploy (see DEPLOYMENT_GUIDE.md for full manifests)
kubectl apply -f deployment.yaml

# 4. Get service URL
kubectl get svc upi-mesh-service

# 5. Monitor
kubectl get pods
kubectl logs deployment/upi-mesh-backend
kubectl describe deployment upi-mesh-backend

# 6. Scale
kubectl scale deployment upi-mesh-backend --replicas=5
```

---

## **CLEANUP COMMANDS**

```bash
# Docker Compose
docker-compose down -v  # Remove containers + volumes

# Standalone containers
docker stop upi-mesh upi-pg
docker rm upi-mesh upi-pg
docker volume rm upi-pg-volume

# Clean up images
docker rmi upi-mesh-backend:latest

# Free up disk
docker system prune -a

# Azure
az group delete -n myResourceGroup --yes

# AWS
aws ecs delete-service --cluster upi-mesh-cluster --service upi-mesh-service --force
aws ecs delete-cluster --cluster upi-mesh-cluster
aws rds delete-db-instance --db-instance-identifier upi-mesh-db --skip-final-snapshot
aws ecr delete-repository --repository-name upi-mesh --force

# Kubernetes
kubectl delete -f deployment.yaml
```

---

## **TROUBLESHOOTING**

```bash
# Check if app is running
docker ps
docker logs upi-mesh-app

# Test database connection
docker exec upi-mesh-db psql -U upiuser -d upimesh -c "SELECT 1"

# Check environment variables
docker inspect upi-mesh-app | grep -i env

# Restart
docker-compose restart upi-mesh-app

# Kill and rebuild
docker-compose down
docker-compose up -d

# Check port
netstat -ano | findstr :8080  # Windows
lsof -i :8080                 # Linux/Mac
```

---

## **VERIFY DEPLOYMENT**

```bash
# Health check
curl http://localhost:8080/actuator/health

# Get all accounts
curl http://localhost:8080/api/accounts

# Get server key
curl http://localhost:8080/api/server-key

# View dashboard
open http://localhost:8080
```

---

## **ONE-LINER PRODUCTION SETUP**

```bash
# Start everything with one command
docker-compose -f docker-compose.yml up -d

# That's it! 🎉
```

---

## **RECOMMENDED DEPLOYMENT PATH**

1. **Local Testing** → `docker-compose up -d` (5 min)
2. **Cloud Staging** → Azure App Service (15 min)
3. **Production** → Kubernetes on AKS (30 min)

See **DEPLOYMENT_GUIDE.md** for detailed instructions.

