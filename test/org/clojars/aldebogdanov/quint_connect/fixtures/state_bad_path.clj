(ns org.clojars.aldebogdanov.quint-connect.fixtures.state-bad-path
  "A state path that is a bare keyword rather than a vector of keys.")

(def ^{:quint/state {:var :lastError :path :err}} status (atom {:err ""}))
