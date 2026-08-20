(ns org.clojars.aldebogdanov.quint-connect.fixtures.destructured
  "A handler destructuring its argument. Annotated handlers take picks
  positionally, so there is no name here to bind a pick to.")

(defn transfer {:quint/action "transfer"} [{:keys [from to amount]}]
  [from to amount])
