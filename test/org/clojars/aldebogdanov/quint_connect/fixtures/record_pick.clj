(ns org.clojars.aldebogdanov.quint-connect.fixtures.record-pick
  "One pick whose value is a record, destructured by the handler. Naming the
  pick in :quint/args is what says so: the destructuring then applies to that
  pick's value, not to the picks map.")

(def seen (atom 0))

(defn recv {:quint/action "recv" :quint/args [:m]} [{:keys [from body]}]
  (swap! seen + body)
  [from body])
