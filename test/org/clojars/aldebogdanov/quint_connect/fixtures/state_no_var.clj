(ns org.clojars.aldebogdanov.quint-connect.fixtures.state-no-var
  "A state annotation that names no spec variable.")

(def ^{:quint/state {:path [:inner]}} status (atom {:inner 1}))
