(ns org.clojars.aldebogdanov.quint-connect.fixtures.args-bad-entry
  "A map entry whose value does not name a pick.")

(defn transfer
  {:quint/action "transfer" :quint/args [{:from "src"}]}
  [{:keys [from]}]
  from)
