(ns org.clojars.aldebogdanov.quint-connect.fixtures.args-string-key
  "A composed map keyed by a string. Quint records decode with keyword keys, so
  keywords are the shape that meets them.")

(defn transfer
  {:quint/action "transfer" :quint/args [{"from" :src}]}
  [{:keys [from]}]
  from)
