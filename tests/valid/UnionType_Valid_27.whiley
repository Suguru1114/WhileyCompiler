type IntBool is int | bool

function f(int y) -> int:
    return y

function f(bool y) -> bool:
    return y

public export method test():
    IntBool x
    //
    x = 123
    assume f(x) == 123
    x = false
    assume f(x) == false
