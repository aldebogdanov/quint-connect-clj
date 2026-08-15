module lru {
  const CAPACITY: int
  const KEYS: Set[str]

  var entries: str -> int
  var recency: List[str]          // least recently used first

  pure def without(l: List[str], k: str): List[str] = l.select(x => x != k)

  pure def restrict(ks: Set[str], m: str -> int): str -> int =
    ks.mapBy(k => m.get(k))

  action init = all {
    entries' = Map(),
    recency' = [],
  }

  // Writing a key makes it the most recently used, and evicts the least
  // recently used one when that puts us over capacity.
  action write(k: str, v: int): bool = all {
    val bumped   = recency.without(k).append(k)
    val evicting = bumped.length() > CAPACITY
    val victim   = bumped.head()
    all {
      entries' = if (evicting)
                   entries.keys().exclude(Set(victim)).restrict(entries).put(k, v)
                 else
                   entries.put(k, v),
      recency' = if (evicting) bumped.slice(1, bumped.length()) else bumped,
    }
  }

  // *Reading* a key also makes it the most recently used. This is the rule
  // that is easy to forget in an implementation.
  action read(k: str): bool = all {
    entries.keys().contains(k),
    entries' = entries,
    recency' = recency.without(k).append(k),
  }

  action absent(k: str): bool = all {
    not(entries.keys().contains(k)),
    entries' = entries,
    recency' = recency,
  }

  action step = {
    nondet k = KEYS.oneOf()
    nondet v = 1.to(9).oneOf()
    any { write(k, v), read(k), absent(k) }
  }

  val neverOverCapacity = entries.keys().size() <= CAPACITY
}

module lruTest {
  import lru(CAPACITY = 3, KEYS = Set("a", "b", "c", "d")).*
}
