type funii is function(int)->(int)

function f(int|null x) -> int:
    return 0

function g(function(int)->int func) -> int:
    return func(1)

public export method test() :
    assume g((funii) &f) == 0
