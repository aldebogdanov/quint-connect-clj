// Reproduces the large-integer serialization quirk of Quint 0.32.0.
// See docs/notes/itf-format.md.
module bigint {
  var atBoundary: int   // 10^15, first value that leaks bignumber.js internals
  var belowBoundary: int
  var unsafe: int       // > Number.MAX_SAFE_INTEGER

  action init = all {
    atBoundary' = 1000000000000000,
    belowBoundary' = 999999999999999,
    unsafe' = 9007199254740993,
  }

  action step = all {
    atBoundary' = atBoundary,
    belowBoundary' = belowBoundary,
    unsafe' = unsafe,
  }
}
