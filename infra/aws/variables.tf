variable "name" {
  description = "Prefix for every resource"
  type        = string
  default     = "ec-order-hub"
}

variable "region" {
  type    = string
  default = "ap-northeast-1"
}

variable "image_tag" {
  description = "Tag of the image pushed to the ECR repository"
  type        = string
}

variable "sakura_base_url" {
  description = "Base URL of the Sakura Mall API"
  type        = string
}

variable "komorebi_base_url" {
  description = "Base URL of the Komorebi Market API"
  type        = string
}

variable "allowed_cidrs" {
  description = "Who may reach the load balancer"
  type        = list(string)
}

variable "db_instance_class" {
  type    = string
  default = "db.t4g.micro"
}

variable "deletion_protection" {
  description = "Protect the database and keep a final snapshot; turn off only for throwaway environments"
  type        = bool
  default     = true
}
