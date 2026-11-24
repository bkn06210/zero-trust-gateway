# GitLab CI가 AWS에 들어올 때 쓰는 신원.
#
# 액세스 키를 GitLab에 저장하지 않는다. 대신 GitLab이 작업마다 "나는 이 프로젝트 main 브랜치의 작업이다"라는
# 서명된 증명서를 발급하고, AWS는 그 서명과 내용을 확인한 뒤 그 순간에만 쓸 수 있는 임시 자격을 준다.
# 새어 나갈 키가 애초에 없다.

# AWS에게 "gitlab.com이 발급한 증명서를 믿어도 된다"고 등록한다.
resource "aws_iam_openid_connect_provider" "gitlab" {
  url            = "https://gitlab.com"
  client_id_list = ["https://gitlab.com"]
  tags           = { Project = "zero-trust" }
}

# GitLab CI가 받는 역할. 할 수 있는 일은 기존 배포 권한과 같다. 이 서버 한 대에 셸 명령 보내기.
resource "aws_iam_role" "gitlab_deploy" {
  name = "zero-trust-gitlab-deploy"
  tags = { Project = "zero-trust" }

  # 누가 이 역할을 받을 수 있는가.
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Federated = aws_iam_openid_connect_provider.gitlab.arn }
      Action    = "sts:AssumeRoleWithWebIdentity"
      Condition = {
        StringEquals = {
          # 증명서가 AWS용으로 발급된 것인가.
          "gitlab.com:aud" = "https://gitlab.com"
          # 증명서에 적힌 출처가 정확히 이 프로젝트의 main 브랜치인가.
          # 여기를 "프로젝트만 맞으면 된다"로 느슨하게 쓰면, 누구든 브랜치를 하나 만들어
          # CI 파일을 고쳐서 이 역할을 받을 수 있다. 그래서 브랜치까지 글자 그대로 비교한다.
          "gitlab.com:sub" = "project_path:${var.gitlab_project_path}:ref_type:branch:ref:main"
        }
      }
    }]
  })
}

resource "aws_iam_role_policy" "gitlab_deploy" {
  name = "send-command-to-gateway"
  role = aws_iam_role.gitlab_deploy.id
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

# GitLab CI 변수 AWS_ROLE_ARN에 넣는다.
output "gitlab_deploy_role_arn" {
  value = aws_iam_role.gitlab_deploy.arn
}
