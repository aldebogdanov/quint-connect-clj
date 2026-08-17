// A token bucket: a rate limiter that hands out tokens and refills them.
//
// lastAction and lastPick are the spec's own bookkeeping, not state the
// implementation has to supply. `quint verify` emits no mbt:: variables at
// all, so a counterexample can only drive an implementation if the spec
// records what it did — see docs/getting-started.md §7.
module tokens {
  const CAPACITY: int

  var tokens: int
  var lastAction: str
  var lastPick: { n: int }

  action init = all {
    tokens' = CAPACITY,
    lastAction' = "init",
    lastPick' = { n: 0 },
  }

  action grant(n: int): bool = all {
    n > 0,
    tokens >= n,
    tokens' = tokens - n,
    lastAction' = "grant",
    lastPick' = { n: n },
  }

  action refuse(n: int): bool = all {
    n > 0,
    tokens < n,
    tokens' = tokens,
    lastAction' = "refuse",
    lastPick' = { n: n },
  }

  action refill(n: int): bool = all {
    n > 0,
    // The cap is the whole point of a bucket, and the easiest line to get wrong.
    tokens' = if (tokens + n > CAPACITY) CAPACITY else tokens + n,
    lastAction' = "refill",
    lastPick' = { n: n },
  }

  action step = {
    nondet n = 1.to(CAPACITY).oneOf()
    any { grant(n), refuse(n), refill(n) }
  }

  val neverOverCapacity = tokens <= CAPACITY
  val neverNegative = tokens >= 0

  // Deliberately too strong: a bucket you can drain is a bucket that reaches
  // zero. Apalache finds the trace that proves it, and that trace is what gets
  // replayed against the implementation.
  val neverStarved = tokens > 0
}

module tokensTest {
  import tokens(CAPACITY = 5).*
}
