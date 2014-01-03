import println from whiley.lang.System

type tenup is int where $ > 10

type msg1 is {tenup op, [int] data}

type msg2 is {int index}

type msgType is msg1 | msg2

function f(msgType m) => string:
    return Any.toString(m)

method main(System.Console sys) => void:
    m1 = {op: 11, data: []}
    m2 = {index: 1}
    sys.out.println(f(m1))
    sys.out.println(f(m2))