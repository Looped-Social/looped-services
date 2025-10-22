#!/usr/bin/env bash
set -euo pipefail

AWS_REGION=${AWS_REGION:-us-east-1}
AWS_ACCOUNT_ID=${AWS_ACCOUNT_ID:?set AWS_ACCOUNT_ID}
ECR_REPO=${ECR_REPO:-looped-api}
IMAGE_TAG=${IMAGE_TAG:-dev}

ECR_URI="$AWS_ACCOUNT_ID.dkr.ecr.$AWS_REGION.amazonaws.com/$ECR_REPO:$IMAGE_TAG"

echo "Logging into ECR..."
aws ecr get-login-password --region "$AWS_REGION" | docker login --username AWS --password-stdin "$AWS_ACCOUNT_ID.dkr.ecr.$AWS_REGION.amazonaws.com"

echo "Building image..."
docker build -t "$ECR_REPO:$IMAGE_TAG" -f apps/api/Dockerfile .

echo "Tagging and pushing..."
docker tag "$ECR_REPO:$IMAGE_TAG" "$ECR_URI"
docker push "$ECR_URI"

echo "Pushed: $ECR_URI"

