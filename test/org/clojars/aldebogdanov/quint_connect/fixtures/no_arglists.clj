(ns org.clojars.aldebogdanov.quint-connect.fixtures.no-arglists
  "A handler defined with def and fn, which records no :arglists, so there is
  nothing to read pick names from.")

(def ^{:quint/action "deposit"} deposit
  (fn [who amount] [who amount]))
