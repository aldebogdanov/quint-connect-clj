(ns uno.michelada.quint-connect.fixtures.duplicate)

(defn deposit-one {:quint/action "deposit"} [who amount] [who amount])
(defn deposit-two {:quint/action "deposit"} [who amount] [who amount])
