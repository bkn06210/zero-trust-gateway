output "public_ip" {
  description = "서버 공인 IP"
  value       = aws_instance.gateway.public_ip
}

output "gateway_url" {
  value = "http://${aws_instance.gateway.public_ip}:8080"
}

output "instance_id" {
  value = aws_instance.gateway.id
}

output "ssh" {
  value = "ssh -i ~/.ssh/zero-trust ec2-user@${aws_instance.gateway.public_ip}"
}
