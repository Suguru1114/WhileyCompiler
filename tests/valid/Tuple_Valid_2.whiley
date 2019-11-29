function swap(int x, int y) -> (int a, int b)
// Make sure values are swapped
ensures (x == b) && (y == a):
    //
    return y,x

public export method test():
    //
    int a, int b = swap(1,2)
    //
    assert a == 2
    //
    assert b == 1
    //
    assert (a,b) == (2,1)
