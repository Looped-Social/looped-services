output "db_url_parameter_name" {
  value = aws_ssm_parameter.db_url.name
}

output "db_url_parameter_arn" {
  value = aws_ssm_parameter.db_url.arn
}

output "auth_issuer_parameter_name" {
  value = aws_ssm_parameter.auth_issuer.name
}

output "auth_issuer_parameter_arn" {
  value = aws_ssm_parameter.auth_issuer.arn
}

output "auth_audience_parameter_name" {
  value = aws_ssm_parameter.auth_audience.name
}

output "auth_audience_parameter_arn" {
  value = aws_ssm_parameter.auth_audience.arn
}

output "auth_jwks_uri_parameter_name" {
  value = aws_ssm_parameter.auth_jwks_uri.name
}

output "auth_jwks_uri_parameter_arn" {
  value = aws_ssm_parameter.auth_jwks_uri.arn
}
