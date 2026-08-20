(ns org.clojars.aldebogdanov.quint-connect.fixtures.args-set
  "A set as an :quint/args entry. Nothing destructures a set positionally, so
  it is not a shape that can be composed.")

(defn deposit {:quint/action "deposit" :quint/args [#{:who}]} [who] who)
