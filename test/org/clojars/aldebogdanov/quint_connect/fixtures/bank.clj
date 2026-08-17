(ns org.clojars.aldebogdanov.quint-connect.fixtures.bank
  "An annotated toy bank. Deliberately written the way application code would
  be: no require of quint-connect, handlers taking their own arguments.")

(def ^{:quint/state :balances} accounts (atom {}))

;; metadata on the reference object rather than the var
(def last-error (atom "" :meta {:quint/state :lastError}))

(defn reset-app! {:quint/init true} []
  (reset! accounts {"alice" 0 "bob" 0})
  (reset! last-error ""))

(defn deposit {:quint/action "deposit"} [who amount]
  (swap! accounts update who + amount)
  (reset! last-error ""))

;; parameter names differ from the pick names, so :quint/args says the order
(defn withdraw {:quint/action "withdraw" :quint/args [:who :amount]} [account n]
  (swap! accounts update account - n)
  (reset! last-error ""))

(defn overdraft {:quint/action "overdraft"} [_who _amount]
  (reset! last-error "insufficient funds"))
