
function f(int[] x) -> bool:
    int y = x[0]
    int z = x[1]
    assert y == z

public export method test():
    assume f([1])
