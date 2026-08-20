(ns org.clojars.aldebogdanov.quint-connect.fixtures.star-nested
  "A whole-state reader whose map sits nested inside the value it reads — the
  shape a system map, or a Choreo-style spec's per-node state, puts you in.")

(def ^{:quint/state {:var :* :path [:app :state]}} system
  (atom {:app {:state {:balances {"alice" 1} :pending 2}}
         :log []}))
