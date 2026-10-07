variable "region" {
  description = "AWS 리전"
  type        = string
  default     = "ap-northeast-2"
}

variable "instance_type" {
  description = "서버 크기. Spring 앱 둘 + PostgreSQL + Redis 에 1GB는 부족해서 2GB"
  type        = string
  default     = "t3.small"
}

variable "admin_cidr" {
  description = "SSH(22)를 허용할 IP 범위. 예: 1.2.3.4/32"
  type        = string
}

variable "ssh_public_key_path" {
  description = "서버에 등록할 SSH 공개키 파일 경로"
  type        = string
}

variable "compose_url" {
  description = "서버가 받아갈 compose 파일 주소"
  type        = string
  default     = "https://raw.githubusercontent.com/bkn06210/zero-trust-gateway/main/docker-compose.prod.yml"
}

variable "jwt_secret" {
  type      = string
  sensitive = true
}

variable "db_password" {
  type      = string
  sensitive = true
}

variable "admin_email" {
  type = string
}

variable "admin_password" {
  type      = string
  sensitive = true
}
