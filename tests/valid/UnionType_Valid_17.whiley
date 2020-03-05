type IntList is int | int[]

function f(int y) -> int:
    return y

function g(int[] z) -> int[]:
    return z

public export method test() :
    IntList x = 123
    assume f((int) x) == 123
    x = [1, 2, 3]
    assume g((int[]) x) == [1,2,3]
