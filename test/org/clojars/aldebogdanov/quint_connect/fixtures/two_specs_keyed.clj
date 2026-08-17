(ns org.clojars.aldebogdanov.quint-connect.fixtures.two-specs-keyed
  "A scoped annotation under a moved :key-ns, proving the sixth key travels
  with the other five.")

(defn open! {:acme.mbt/init true :acme.mbt/driver :ledger} [] :opened)

(defn post {:acme.mbt/action "post"} [amount] amount)
