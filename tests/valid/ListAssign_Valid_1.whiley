

public export method test() -> void:
    int[] arr1 = [1, 2, 3]
    int[] arr2 = arr1
    arr2[2] = 2
    assert arr2[2] != |arr1|
    assert arr1 == [1,2,3]
    assert arr2 == [1,2,2]
