(ns org.clojars.aldebogdanov.quint-connect.fixtures.variadic
  "A variadic handler. A rest parameter names no pick.")

(defn deposit {:quint/action "deposit"} [who & more]
  [who more])
