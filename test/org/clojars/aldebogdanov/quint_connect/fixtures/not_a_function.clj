(ns org.clojars.aldebogdanov.quint-connect.fixtures.not-a-function
  "An action annotation on a var that holds no function — the mistake of
  annotating the state instead of the handler.")

(def ^{:quint/action "deposit"} balances {"alice" 0})
