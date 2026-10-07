#!/bin/bash
# 서버가 처음 켜질 때 한 번 실행된다. 로그: /var/log/cloud-init-output.log
set -euxo pipefail

# 1. Docker 설치
dnf install -y docker
systemctl enable --now docker
usermod -aG docker ec2-user

# 2. docker compose 플러그인
mkdir -p /usr/local/lib/docker/cli-plugins
curl -sSL "https://github.com/docker/compose/releases/latest/download/docker-compose-linux-x86_64" \
  -o /usr/local/lib/docker/cli-plugins/docker-compose
chmod +x /usr/local/lib/docker/cli-plugins/docker-compose

# 3. 스왑 2GB. 메모리가 모자랄 때 앱이 죽는 대신 느려지게 하는 안전장치.
fallocate -l 2G /swapfile
chmod 600 /swapfile
mkswap /swapfile
swapon /swapfile
echo '/swapfile none swap sw 0 0' >> /etc/fstab

# 4. 앱 폴더와 compose 파일
mkdir -p /opt/zero-trust
cd /opt/zero-trust
curl -sSL "${compose_url}" -o docker-compose.yml

# 5. 비밀값을 Parameter Store에서 읽어 .env 작성. 서버 역할(IAM role)로 읽으므로 키가 필요 없다.
: > .env
chmod 600 .env
for key in JWT_SECRET DB_NAME DB_USER DB_PASSWORD ADMIN_EMAIL ADMIN_PASSWORD; do
  value=$(aws ssm get-parameter --region "${region}" --name "${param_prefix}/$key" \
            --with-decryption --query 'Parameter.Value' --output text)
  echo "$key=$value" >> .env
done
chown -R ec2-user:ec2-user /opt/zero-trust

# 6. 기동
docker compose pull
docker compose up -d
