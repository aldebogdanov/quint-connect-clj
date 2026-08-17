module counter {
  var count: int
  var lastOp: str

  action init = all {
    count' = 0,
    lastOp' = "",
  }

  action add(n: int): bool = all {
    n > 0,
    count' = count + n,
    lastOp' = "add",
  }

  action take(n: int): bool = all {
    n > 0,
    count >= n,
    count' = count - n,
    lastOp' = "take",
  }

  action refuse(n: int): bool = all {
    n > 0,
    count < n,
    count' = count,          // refusing changes nothing
    lastOp' = "refused",
  }

  action step = {
    nondet n = 1.to(10).oneOf()
    any { add(n), take(n), refuse(n) }
  }
}
