(ns bank.core
  "A toy bank, annotated for quint-connect and matching dev/fixtures/bank.qnt.

  Note what is absent: any :require of quint-connect. The annotations are inert
  keywords, and nothing here ever calls the library.")

(def ^{:quint/state :balances} accounts (atom {}))
(def ^{:quint/state :lastError} last-error (atom ""))

(defn open! {:quint/init true} []
  (reset! accounts {"alice" 0 "bob" 0})
  (reset! last-error ""))

(defn deposit {:quint/action "deposit"} [who amount]
  (swap! accounts update who + amount)
  (reset! last-error ""))

(defn withdraw {:quint/action "withdraw"} [who amount]
  (swap! accounts update who - amount)
  (reset! last-error ""))

(defn overdraft {:quint/action "overdraft"} [_who _amount]
  (reset! last-error "insufficient funds"))
