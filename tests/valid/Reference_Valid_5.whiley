public export method test():
    &{int x} c = new {x: 5}
    &{int x} d = c
    *c = {x: 4}
    assume (*c) == {x:4}
    assume (*d) == {x:4}

