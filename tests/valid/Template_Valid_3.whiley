type Link<T> is { LinkedList<T> next, T data }
type LinkedList<T> is null | Link<T>

public export method test():
    LinkedList<int> l1 = null
    Link<int> l2 = { next: l1, data: 0 }
    Link<int> l3 = { next: l2, data: 1 }
    //
    assert l1 == null
    assert l2.next == null
    assert l2.data == 0
    assert l3.next == l2
    assert (l3.next is Link<int>) && l3.next.next == null
    assert l3.data == 1
