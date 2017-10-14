function f(int[] xs) -> (int r)
requires |xs| > 0
ensures r < |xs|:
    //
    return 0

function g(int[] xs) -> (int r)
requires |xs| > 0:
    //
    xs = [0; f(xs)+1]
    return xs[0]

public export method test():
    //
    assume g([0]) == 0
    //
    assume g([1,0]) == 0
