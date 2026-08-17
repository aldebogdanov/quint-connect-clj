// A bounded queue, specified once and instantiated twice. The two modules
// differ only in CAPACITY, which is exactly the case :quint/driver exists for:
// one implementation, one spec file, two drivers that need different setup.
module queue {
  const CAPACITY: int

  var items: List[int]

  action init = items' = []

  action push(x: int): bool = all {
    length(items) < CAPACITY,
    items' = items.append(x),
  }

  action reject(x: int): bool = all {
    length(items) >= CAPACITY,
    items' = items,
  }

  action popItem = all {
    length(items) > 0,
    items' = items.slice(1, length(items)),
  }

  action step = {
    nondet x = 1.to(9).oneOf()
    any { push(x), reject(x), popItem }
  }

  val neverOverCapacity = length(items) <= CAPACITY
}

module smallQueue { import queue(CAPACITY = 2).* }
module largeQueue { import queue(CAPACITY = 5).* }
