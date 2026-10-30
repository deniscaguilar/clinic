#!/bin/sh
# Roda quando o LocalStack fica pronto (ready.d). Em produção o bucket vem da infraestrutura (OpenTofu), nunca da aplicação.
set -e
awslocal s3api create-bucket --bucket clinic-assets --create-bucket-configuration LocationConstraint=sa-east-1 || true
# Remetente das notificações (SES no LocalStack exige identidade verificada). Em produção a identidade vem da infraestrutura.
awslocal ses verify-email-identity --email-address no-reply@clinica.local || true
