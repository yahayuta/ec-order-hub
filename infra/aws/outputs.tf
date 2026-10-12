output "url" {
  value = "http://${aws_lb.this.dns_name}"
}

output "ecr_repository_url" {
  value = aws_ecr_repository.app.repository_url
}

output "db_secret_arn" {
  description = "Secrets Manager secret holding the database password"
  value       = aws_db_instance.this.master_user_secret[0].secret_arn
}
