
variable "aws_region" {
  type = string
}

variable "aws_availability_zone" {
  type = string
}

variable "instance_name" {
  type        = string
  description = "Name of the lightsail instance"
}

variable "ip_address_type" {
  type        = string
  description = "The IP address type of the Lightsail Instance. Valid Values: dualstack | ipv4"
  default = "ipv4"
}

variable "blueprint" {
  type        = string
  description = "One of the blueprints listed by the AWS cli - aws lightsail get-blueprints"
  default = "ubuntu_22_04"
}

variable "bundle_id" {
  type        = string
  description = "The bundle id of the instance. Use AWS cli - aws lightsail get-bundles"
  default = "small_3_0"
}

variable "static_ip_name" {
  type        = string
  description = "Unique name for the lightsail static ip resource"
}

variable "publickey" {
  type        = string
  description = "Public key for access lightsail instance"
  default = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIO9/S8kIxj/lD2DFO8tBWfE2QoyP0tu5AqQf52KlLj8w sburke@chainring.co"
}

variable "dns_zone" {
  type = string
  description = "primary existing dns zone to attach to this instance"
}

