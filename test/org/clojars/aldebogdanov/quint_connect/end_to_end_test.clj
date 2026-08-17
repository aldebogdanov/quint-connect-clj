(ns org.clojars.aldebogdanov.quint-connect.end-to-end-test
  "The whole thing: a Quint spec, an annotated implementation, and a real
   deftest. Needs quint on PATH."
  (:require [bank.core :as bank]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :as t :refer [deftest is testing]]
            [org.clojars.aldebogdanov.quint-connect.core :as q]
            [org.clojars.aldebogdanov.quint-connect.report :as report]
            [org.clojars.aldebogdanov.quint-connect.test :as qt])
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

;; --- a scripted run, from a spec that records its own actions -------------

(q/defdriver tracked
  {:spec        "dev/fixtures/tracked.qnt"
   :main        "trackedRuns"
   :scan        '[bank.core]          ; the same implementation, unchanged
   :action-path [:lastAction]
   :nondet-path [:lastPick]})

(deftest scripted-trace-replays-without-quint
  (let [r (q/replay-file tracked "dev/fixtures/tracked_test_depositThenOverdraftTest.itf.json")]
    (is (:ok? r))
    (is (= 4 (:steps r)))
    (is (= {"deposit" 1 "withdraw" 1 "overdraft" 1} (get-in r [:coverage :used])))))

(deftest ^:integration check-run-drives-the-implementation-from-quint-test
  (let [r (qt/check-run tracked {:test "depositThenOverdraftTest"})]
    (is (:ok? r))
    (is (= 1 (:traces r)) "a scripted run is one trace by definition")
    (is (= 4 (:steps r)))
    (is (empty? (get-in r [:coverage :unused])))))

(deftest ^:integration check-run-reports-a-divergence-the-same-way
  (with-redefs [bank/deposit buggy-deposit]
    (let [r (q/check-run tracked {:test "depositThenOverdraftTest"})
          f (:failure r)]
      (is (false? (:ok? r)))
      (is (= 1 (:step f)) "the scripted deposit is step 1")
      (is (= "deposit" (:action f)))
      (is (= {:who "alice" :amount 50} (:picks f)))
      (is (= #'bank/deposit (:handler f)))
      (is (= {:balances {"alice" 50 "bob" 0}} (:expected f)))
      (is (= {:balances {"alice" 51 "bob" 0}} (:actual f))))))

(deftest key-fn-reaches-the-decoder-from-the-driver
  ;; collide_0.itf.json is recorded to be undecodable without a :key-fn, so the
  ;; error it produces says whether the driver's option arrived at all.
  (let [file  "dev/fixtures/collide_0.itf.json"
        error #(try (q/replay-file % file) nil
                    (catch clojure.lang.ExceptionInfo e (:quint/error (ex-data e))))]
    (is (= :name-collision (error (q/driver {}))))
    (is (= :no-init (error (q/driver {:key-fn (fn [n] (keyword (str/replace n "::" "-")))})))
        "past decoding, and on to a complaint about the trace itself")))

(deftest forgetting-the-paths-fails-in-two-legible-ways
  (let [file "dev/fixtures/tracked_test_depositThenOverdraftTest.itf.json"
        base {:spec "dev/fixtures/tracked.qnt" :main "trackedRuns" :scan '[bank.core]}]

    (testing "first the spec's own bookkeeping arrives as state nothing supplies"
      (let [r (q/replay-file (q/driver base) file)]
        (is (false? (:ok? r)))
        (is (= 0 (:step (:failure r))))
        (is (contains? (:expected (:failure r)) :lastAction)
            "which is the argument for splitting those variables out")))

    (testing "and once they are ignored, the missing action names the fix"
      (let [d (q/driver (assoc base :ignore #{:lastAction :lastPick}))
            e (try (q/replay-file d file)
                   (catch clojure.lang.ExceptionInfo ex ex))]
        (is (= :unknown-action (:quint/error (ex-data e))))
        (is (str/includes? (ex-message e) ":action-path"))))))

(deftest ^:integration check-aggregates-across-traces
  (let [r (q/check bank {:traces 3 :max-steps 6 :seed 7})]
    (is (:ok? r))
    (is (= 3 (:traces r)))
    (is (= 7 (:seed r)) "the seed is reported so a failure is reproducible")
    (is (pos? (:steps r)))
    (is (pos? (apply + (vals (get-in r [:coverage :used])))))))

;; --- verify: an invariant, and the counterexample when it does not hold ----

(q/defdriver checked
  {:spec        "dev/fixtures/tracked.qnt"
   :main        "trackedTest"
   :scan        '[bank.core]          ; still the same implementation
   :action-path [:lastAction]
   :nondet-path [:lastPick]})

(deftest committed-counterexample-replays-without-apalache
  ;; Recorded from `quint verify --invariant=underFifty`. It carries no mbt::
  ;; and no #meta.source, which is what :action-path is for.
  (let [r (q/replay-file checked "dev/fixtures/tracked_verify_underFifty.itf.json")]
    (is (:ok? r) "the implementation reproduces the spec's own bug")
    (is (= 2 (:steps r)))
    (is (= {"deposit" 1} (get-in r [:coverage :used])))))

(deftest ^:slow a-violated-invariant-is-not-ok-even-when-the-code-agrees
  (let [r (q/verify checked {:invariant "underFifty" :max-steps 2})]
    (is (false? (:ok? r)) "a violated invariant fails, whoever is at fault")
    (is (= {:holds? false} (select-keys (:invariant r) [:holds?])))
    (is (= "underFifty" (get-in r [:invariant :name])))
    (is (nil? (:failure r))
        "the implementation matched the counterexample, so nothing diverged")
    (is (= 1 (:traces r)))
    (testing "and the message says which of the two facts it is"
      (let [s (report/result-str r)]
        (is (str/includes? s "underFifty"))
        (is (str/includes? s "reproduces it faithfully"))))))

(deftest ^:slow an-invariant-that-holds-is-a-pass-with-no-trace
  (let [r (q/verify checked {:invariant "noNegatives" :max-steps 2})]
    (is (:ok? r))
    (is (true? (get-in r [:invariant :holds?])))
    (is (= 0 (:traces r)) "holding writes no trace, and that is the pass")
    (is (nil? (:failure r)))))

(deftest ^:slow verify-also-reports-an-implementation-that-disagrees
  (with-redefs [bank/deposit buggy-deposit]
    (let [r (q/verify checked {:invariant "underFifty" :max-steps 2})]
      (is (false? (:ok? r)))
      (is (false? (get-in r [:invariant :holds?])) "the invariant still fails")
      (is (some? (:failure r)) "and now the implementation does too")
      (is (= "deposit" (:action (:failure r))))
      (is (str/includes? (report/result-str r) "does not match the counterexample")))))

(deftest ^:slow an-unknown-invariant-is-typed-not-a-counterexample
  ;; Both exit 1; only the absence of a trace tells them apart.
  (let [e (try (q/verify checked {:invariant "noSuchInvariant" :max-steps 2})
               (catch clojure.lang.ExceptionInfo ex ex))]
    (is (= :quint-failed (:quint/error (ex-data e))))
    (is (str/includes? (ex-message e) "never checked"))))

(deftest ^:slow the-spec-directory-stays-clean
  ;; Apalache writes _apalache-out/ into its working directory, which is why
  ;; verify runs in a scratch directory instead of the spec's own.
  (q/verify checked {:invariant "noNegatives" :max-steps 2})
  (is (not (.exists (io/file "dev/fixtures/_apalache-out")))
      "the logs went to the scratch directory and died with it"))
