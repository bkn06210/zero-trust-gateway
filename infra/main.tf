# 서버 한 대, 방화벽, 비밀값 저장소. `terraform apply`로 만들고 `terraform destroy`로 전부 지운다.

terraform {
  required_version = ">= 1.6"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.0"
    }
  }
}

provider "aws" {
  region = var.region
}

# ---- 네트워크: 계정에 기본으로 있는 VPC를 쓴다 ----
data "aws_vpc" "default" {
  default = true
}

data "aws_subnets" "default" {
  filter {
    name   = "vpc-id"
    values = [data.aws_vpc.default.id]
  }
}

# 최신 Amazon Linux 2023 이미지. AWS가 관리하는 공개 파라미터에서 읽는다.
data "aws_ssm_parameter" "al2023" {
  name = "/aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-x86_64"
}

# ---- 방화벽 ----
# 여는 문은 둘뿐이다. 8080은 전 세계에, 22는 내 IP에만. 8081, 5432, 6379는 열지 않는다.
resource "aws_security_group" "gateway" {
  name        = "zero-trust-gateway"
  description = "zero-trust gateway: 8080 public, 22 admin only"
  vpc_id      = data.aws_vpc.default.id

  ingress {
    description = "gateway"
    from_port   = 8080
    to_port     = 8080
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  ingress {
    description = "ssh from admin"
    from_port   = 22
    to_port     = 22
    protocol    = "tcp"
    cidr_blocks = [var.admin_cidr]
  }

  # 나가는 연결은 전부 허용. 이미지를 받고 패키지를 설치하려면 필요하다.
  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = { Project = "zero-trust" }
}

# ---- SSH 키: 비밀번호 로그인은 없다. 내 PC의 키 파일과 짝이 맞아야만 열린다 ----
resource "aws_key_pair" "admin" {
  key_name   = "zero-trust-admin"
  public_key = file(var.ssh_public_key_path)
}

# ---- 비밀값: SSM Parameter Store에 암호화해서 둔다 ----
# 서버는 부팅할 때 여기서 읽어 .env를 만든다. 비밀값이 Terraform 코드나 서버 생성 스크립트에 그대로 박히지 않는다.
locals {
  secrets = {
    JWT_SECRET     = var.jwt_secret
    DB_NAME        = "authdb"
    DB_USER        = "authuser"
    DB_PASSWORD    = var.db_password
    ADMIN_EMAIL    = var.admin_email
    ADMIN_PASSWORD = var.admin_password
  }
}

resource "aws_ssm_parameter" "env" {
  for_each = local.secrets
  name     = "/zero-trust/${each.key}"
  type     = "SecureString"
  value    = each.value
  tags     = { Project = "zero-trust" }
}

# ---- 서버가 가질 권한: 위 파라미터를 읽는 것, 그리고 SSM으로 원격 명령을 받는 것 ----
# 키를 서버에 두는 게 아니라 "이 서버는 이걸 읽어도 된다"는 역할을 붙인다.
resource "aws_iam_role" "instance" {
  name = "zero-trust-instance"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "ec2.amazonaws.com" }
      Action    = "sts:AssumeRole"
    }]
  })
}

resource "aws_iam_role_policy" "read_params" {
  name = "read-zero-trust-params"
  role = aws_iam_role.instance.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect   = "Allow"
      Action   = ["ssm:GetParameter", "ssm:GetParameters"]
      Resource = "arn:aws:ssm:${var.region}:*:parameter/zero-trust/*"
    }]
  })
}

# SSM 에이전트가 AWS와 통신할 수 있게 하는 AWS 관리 정책. 포트 22 없이 원격 명령을 보내는 데 쓴다.
resource "aws_iam_role_policy_attachment" "ssm_core" {
  role       = aws_iam_role.instance.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"
}

resource "aws_iam_instance_profile" "instance" {
  name = "zero-trust-instance"
  role = aws_iam_role.instance.name
}

# ---- 서버 ----
resource "aws_instance" "gateway" {
  ami                    = data.aws_ssm_parameter.al2023.value
  instance_type          = var.instance_type
  subnet_id              = data.aws_subnets.default.ids[0]
  vpc_security_group_ids = [aws_security_group.gateway.id]
  key_name               = aws_key_pair.admin.key_name
  iam_instance_profile   = aws_iam_instance_profile.instance.name

  # 서버 안에서 누구나 읽을 수 있는 메타데이터를 통해 역할 자격증명을 훔치는 공격을 막는다 (IMDSv2 강제).
  metadata_options {
    http_tokens = "required"
  }

  root_block_device {
    volume_size = 16
    encrypted   = true
  }

  # 처음 켜질 때 한 번 실행되는 설치 스크립트.
  user_data = templatefile("${path.module}/user_data.sh", {
    region       = var.region
    compose_url  = var.compose_url
    param_prefix = "/zero-trust"
  })

  tags = {
    Name    = "zero-trust-gateway"
    Project = "zero-trust"
  }

  depends_on = [aws_ssm_parameter.env]
}
