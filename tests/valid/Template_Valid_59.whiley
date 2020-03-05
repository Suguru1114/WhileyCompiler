type Option<T> is null | { T item }
type OboolOint is Option<bool>|Option<int>

public export method test():
    OboolOint x = (Option<int>) null
    //
    assume x is Option<int>
    //
    x = (Option<bool>) null
    //
    assume x is Option<bool>
    