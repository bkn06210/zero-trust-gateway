# GitHub Actions가 배포할 때 쓰는 권한.
#
# 원래 계획은 OIDC였다: GitHub이 "나는 이 저장소 main 브랜치의 워크플로다"라고 증명하면 AWS가 그 순간에만
# 쓸 수 있는 임시 자격을 주는 방식이라 저장되는 키가 없다. 그런데 새 가입 구조의 프로젝트 계정에는
# 신원 공급자 등록(iam:CreateOpenIDConnectProvider)을 막는 정책이 걸려 있어 쓸 수 없었다.
#
# 차선: 배포 전용 IAM 사용자. 할 수 있는 일을 "이 서버 한 대에 셸 명령 보내기"로 좁혀서,
# 키가 유출돼도 서버를 만들거나 지우거나 다른 데 접근할 수 없게 한다. 키는 GitHub Secrets에만 둔다.

resource "aws_iam_user" "github_deploy" {
  name = "zero-trust-github-deploy"
  tags = { Project = "zero-trust" }
}

resource "aws_iam_user_policy" "github_deploy" {
  name = "send-command-to-gateway"
  user = aws_iam_user.github_deploy.name
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect = "Allow"
        Action = "ssm:SendCommand"
        Resource = [
          aws_instance.gateway.arn,
          "arn:aws:ssm:${var.region}::document/AWS-RunShellScript"
        ]
      },
      {
        Effect   = "Allow"
        Action   = ["ssm:GetCommandInvocation", "ssm:ListCommandInvocations"]
        Resource = "*"
      }
    ]
  })
}

resource "aws_iam_access_key" "github_deploy" {
  user = aws_iam_user.github_deploy.name
}

# 아래 둘은 GitHub Secrets에 넣는다. `terraform output -raw` 로만 꺼내고 화면에 찍지 않는다.
output "github_deploy_access_key_id" {
  value     = aws_iam_access_key.github_deploy.id
  sensitive = true
}

output "github_deploy_secret_access_key" {
  value     = aws_iam_access_key.github_deploy.secret
  sensitive = true
}
