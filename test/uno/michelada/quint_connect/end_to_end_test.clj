(ns uno.michelada.quint-connect.end-to-end-test
  "The whole thing: a Quint spec, an annotated implementation, and a real
   deftest. Needs quint on PATH."
  (:require [bank.core :as bank]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [uno.michelada.quint-connect.core :as q]
            [uno.michelada.quint-connect.report :as report]
            [uno.michelada.quint-connect.test :as qt]))

(q/defdriver bank
  {:spec "dev/fixtures/bank.qnt"
   :main "bankTest"
   :scan '[bank.core]})

;; --- this is what a user writes -------------------------------------------

(deftest ^:integration bank-conforms-to-its-spec
  (qt/check bank {:traces 5 :max-steps 8 :seed 42}))

;; --- and this is what they see when it does not ---------------------------

(deftest ^:integration injected-bug-names-everything-needed-to-fix-it
  (with-redefs [bank/deposit (fn [who amount]
                               (swap! bank/accounts update who + amount 1)
                               (reset! bank/last-error ""))]
    (let [r (q/check bank {:traces 5 :max-steps 8 :seed 42})
          f (:failure r)
          s (report/result-str r)]
      (is (false? (:ok? r)))

      (testing "the result identifies the divergence"
        (is (= "deposit" (:action f)))
        (is (= #'bank/deposit (:handler f)))
        (is (int? (:step f)))
        (is (= {:balances #'bank/accounts} (:readers f)))
        (is (some? (:diff f))))

      (testing "the failing trace is carried, since the temp dir is gone"
        (is (str/ends-with? (:trace-name f) ".itf.json"))
        (is (str/includes? (:trace-json f) "#meta")))

      (testing "the message names action, var, step, diff and the reproduce line"
        (is (str/includes? s "deposit"))
        (is (str/includes? s "bank.core/deposit"))
        (is (str/includes? s "bank.core/accounts"))
        (is (str/includes? s "step "))
        (is (str/includes? s "expected"))
        (is (str/includes? s "reproduce"))
        (is (str/includes? s "--seed=42"))))))

;; --- replay needs no quint ------------------------------------------------

(deftest committed-trace-replays-without-quint
  (let [r (q/replay-file bank "dev/fixtures/bank_run_0.itf.json")]
    (is (:ok? r))
    (is (= 5 (:steps r)))))

(deftest ^:integration check-aggregates-across-traces
  (let [r (q/check bank {:traces 3 :max-steps 6 :seed 7})]
    (is (:ok? r))
    (is (= 3 (:traces r)))
    (is (= 7 (:seed r)) "the seed is reported so a failure is reproducible")
    (is (pos? (:steps r)))
    (is (pos? (apply + (vals (get-in r [:coverage :used])))))))
