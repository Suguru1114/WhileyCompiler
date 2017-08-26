
constant ADD is 1

constant SUB is 2

constant MUL is 3

constant DIV is 4

type bop is ({int op, int rhs, int lhs} r) where ADD <= r.op && r.op <= DIV

function f(bop b) -> bop:
    return b

public export method test():
    bop b = {op: 0, rhs: 2, lhs: 1}
    f(b)
