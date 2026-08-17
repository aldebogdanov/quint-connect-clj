(ns uno.michelada.quint-connect.end-to-end-test
  "The whole thing: a Quint spec, an annotated implementation, and a real
   deftest. Needs quint on PATH."
  (:require [bank.core :as bank]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :as t :refer [deftest is testing]]
            [uno.michelada.quint-connect.core :as q]
            [uno.michelada.quint-connect.report :as report]
            [uno.michelada.quint-connect.test :as qt])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

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

;; --- and a failure becomes one of those committed traces ------------------

(def ^:private buggy-deposit
  "The same off-by-one the test above injects. Named here because two tests use
  it: the one that records the artifact and the one that replays it."
  (fn [who amount]
    (swap! bank/accounts update who + amount 1)
    (reset! bank/last-error "")))

(def ^:private committed
  "Written by the run below into the ignored `failures/` drop zone, then
  promoted by hand. That two-step is the workflow in
  docs/getting-started.md §6."
  "test-resources/quint-connect/bank-seed42-trace0.itf.json")

(deftest ^:integration a-failure-writes-the-trace-that-reproduces-it
  (let [dir (str (Files/createTempDirectory "quint-connect-" (into-array FileAttribute [])))
        ;; qt/check asserts, and here the divergence is the point: swallow the
        ;; report so this deftest can assert on the artifact instead.
        run (fn [opts] (with-redefs [t/do-report (fn [_])] (qt/check bank opts)))]
    (with-redefs [bank/deposit buggy-deposit]
      (let [r    (run {:traces 5 :max-steps 8 :seed 42 :save-failure dir})
            path (get-in r [:failure :saved])]
        (is (false? (:ok? r)))
        (is (= (:trace-json (:failure r)) (slurp path))
            "the trace that was in memory is now on disk")
        (is (str/includes? (report/result-str r) path)
            "and the failure message says where")

        (testing "replaying the file finds the same divergence, with no quint"
          (let [r2 (q/replay-file bank path)]
            (is (false? (:ok? r2)))
            (is (= (:step (:failure r)) (:step (:failure r2))))
            (is (= (:action (:failure r)) (:action (:failure r2))))))

        (testing ":save-failure false writes nothing"
          (let [dropped (fn [] (set (.listFiles (io/file qt/default-failure-dir))))
                before  (dropped)
                r3      (run {:traces 5 :max-steps 8 :seed 42 :save-failure false})]
            (is (false? (:ok? r3)))
            (is (nil? (get-in r3 [:failure :saved])))
            (is (= 1 (count (.listFiles (io/file dir)))))
            (is (= before (dropped)) "not into the default directory either")))))))

(deftest the-committed-failure-is-a-deterministic-test
  (testing "against the bug that produced it"
    (with-redefs [bank/deposit buggy-deposit]
      (let [r (q/replay-file bank committed)]
        (is (false? (:ok? r)))
        (is (= "deposit" (:action (:failure r)))))))
  (testing "and against the implementation as written"
    (qt/replay-file bank committed)))

(deftest ^:integration check-aggregates-across-traces
  (let [r (q/check bank {:traces 3 :max-steps 6 :seed 7})]
    (is (:ok? r))
    (is (= 3 (:traces r)))
    (is (= 7 (:seed r)) "the seed is reported so a failure is reproducible")
    (is (pos? (:steps r)))
    (is (pos? (apply + (vals (get-in r [:coverage :used])))))))
