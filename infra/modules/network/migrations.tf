moved {
  from = aws_eip.nat
  to   = aws_eip.nat_single[0]
}

moved {
  from = aws_nat_gateway.this
  to   = aws_nat_gateway.single[0]
}
