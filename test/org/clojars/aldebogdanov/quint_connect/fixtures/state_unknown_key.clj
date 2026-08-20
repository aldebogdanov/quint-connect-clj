(ns org.clojars.aldebogdanov.quint-connect.fixtures.state-unknown-key
  "A state annotation whose key is misspelled, so nothing in it is read.")

(def ^{:quint/state {:variable :balances}} accounts (atom {}))
