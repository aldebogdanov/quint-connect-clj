(ns org.clojars.aldebogdanov.quint-connect.fixtures.args-not-a-vector
  "A :quint/args that is not a vector, which threw IllegalArgumentException at
  replay rather than saying anything at construction.")

(defn deposit {:quint/action "deposit" :quint/args :who} [who] who)
