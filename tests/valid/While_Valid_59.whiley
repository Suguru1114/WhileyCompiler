property sum(int[] a, int[] b, int[] c, int n)
where all { i in 0..n | c[i] == a[i] + b[i] }

property equal(int[] a, int[] b, int n)
where all { i in n..|a| | a[i] == b[i] }

function arraySum2(int[] a, int[] b) -> (int[] c)
requires |a| == |b|
ensures |c| == |a|
ensures sum(a,b,c,|a|):
    c = a
    int k = 0
    while k < |a|
    where |c| == |a| && 0 <= k && k <= |a|
    where sum(a,b,c,k) && equal(a,c,k):
        c[k] = c[k] + b[k]
        k = k+1
    return c

public export method test():
    //
    int[] xs = [1,2,3]
    int[] ys = [4,5,6]
    //
    assert arraySum2(xs,ys) == [5,7,9]
    //
    assert arraySum2(xs,xs) == [2,4,6]
    //
    assert arraySum2(ys,ys) == [8,10,12]
    //
    assert arraySum2(ys,xs) == [5,7,9]